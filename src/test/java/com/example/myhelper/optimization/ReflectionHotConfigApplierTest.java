package com.example.myhelper.optimization;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 通用热应用只能写入可变数值 @Value 字段，不能把字符串或 final 字段误登记。 */
class ReflectionHotConfigApplierTest {

    @Test
    void discoversAndAppliesOnlyMutableNumericValueFields() {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        factory.registerBeanDefinition("sample", new RootBeanDefinition(SampleBean.class));
        SampleBean bean = factory.getBean(SampleBean.class);

        ReflectionHotConfigApplier applier = new ReflectionHotConfigApplier(factory);
        applier.afterSingletonsInstantiated();

        assertTrue(applier.supports("myhelper.sample.retries"));
        assertFalse(applier.supports("myhelper.sample.name"));
        assertFalse(applier.supports("myhelper.sample.fixed"));
        assertTrue(applier.apply("myhelper.sample.retries", 7.4));
        assertEquals(7, bean.retries);
        assertEquals("unchanged", bean.name);
        assertEquals(2, bean.fixed);
    }

    static class SampleBean {
        @Value("${myhelper.sample.retries:3}")
        int retries = 3;

        @Value("${myhelper.sample.name:unchanged}")
        String name = "unchanged";

        @Value("${myhelper.sample.fixed:2}")
        final int fixed = 2;
    }
}
