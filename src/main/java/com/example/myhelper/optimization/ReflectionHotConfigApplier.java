package com.example.myhelper.optimization;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.stereotype.Component;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 为标量 {@link Value} 字段提供通用热应用能力。
 *
 * <p>启动后只登记已经创建的单例 Bean 中、形如 {@code ${a.b:default}} 的数值字段。
 * 它不是任意反射写入器：字符串、对象、集合和配置记录都不会登记；目录服务还会先拦截
 * 密钥、连接和需要重启的路径。</p>
 */
@Component
public class ReflectionHotConfigApplier implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(ReflectionHotConfigApplier.class);
    private static final Pattern PROPERTY_EXPRESSION = Pattern.compile("^\\$\\{([^:}]+)(?::[^}]*)?}$");

    private final ConfigurableListableBeanFactory beanFactory;
    private final Map<String, List<FieldTarget>> targetsByPath = new ConcurrentHashMap<>();

    public ReflectionHotConfigApplier(ConfigurableListableBeanFactory beanFactory) {
        this.beanFactory = beanFactory;
    }

    @Override
    public void afterSingletonsInstantiated() {
        for (String beanName : beanFactory.getBeanDefinitionNames()) {
            if (!beanFactory.containsSingleton(beanName)) continue;
            Object bean = beanFactory.getSingleton(beanName);
            if (bean != null) scan(bean);
        }
        log.info("🧠 元优化器已登记 {} 个可反射热应用的配置路径", targetsByPath.size());
    }

    public boolean supports(String propertyPath) {
        return targetsByPath.containsKey(propertyPath);
    }

    /** 对同一路径的所有匹配字段同时应用，避免相同配置在多个服务中出现不一致。 */
    public boolean apply(String propertyPath, double value) {
        List<FieldTarget> targets = targetsByPath.get(propertyPath);
        if (targets == null || targets.isEmpty()) return false;
        try {
            for (FieldTarget target : targets) target.write(value);
            log.info("♨️ 已热应用配置 {} 到 {} 个 Bean 字段", propertyPath, targets.size());
            return true;
        } catch (RuntimeException e) {
            log.warn("配置热应用失败 {}: {}", propertyPath, e.getMessage());
            return false;
        }
    }

    private void scan(Object bean) {
        for (Class<?> type = bean.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                Value value = field.getAnnotation(Value.class);
                if (value == null || !isNumeric(field.getType())
                        || Modifier.isStatic(field.getModifiers()) || Modifier.isFinal(field.getModifiers())) continue;
                Matcher matcher = PROPERTY_EXPRESSION.matcher(value.value());
                if (!matcher.matches()) continue;
                if (!field.trySetAccessible()) continue;
                targetsByPath.computeIfAbsent(matcher.group(1), ignored -> new ArrayList<>())
                        .add(new FieldTarget(bean, field));
            }
        }
    }

    private boolean isNumeric(Class<?> type) {
        return type == int.class || type == Integer.class || type == long.class || type == Long.class
                || type == float.class || type == Float.class || type == double.class || type == Double.class;
    }

    private record FieldTarget(Object bean, Field field) {
        void write(double value) {
            try {
                Class<?> type = field.getType();
                if (type == int.class || type == Integer.class) field.set(bean, (int) Math.round(value));
                else if (type == long.class || type == Long.class) field.set(bean, Math.round(value));
                else if (type == float.class || type == Float.class) field.set(bean, (float) value);
                else if (type == double.class || type == Double.class) field.set(bean, value);
                else throw new IllegalArgumentException("不支持字段类型: " + type.getName());
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("无法写入热配置字段: " + field.getName(), e);
            }
        }
    }
}
