package com.example.myhelper.persistence;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.neo4j.driver.TransactionCallback;
import org.neo4j.driver.TransactionContext;
import org.neo4j.driver.Value;

/** Driver 边界检查：完整 JSON 进出数据库，不模拟 Cypher 执行或声称真实服务验证。 */
public final class Neo4jEntityStoreCheck {
    public static void main(String[] args) {
        Map<String, String> storedJson = new HashMap<>();
        List<String> reads = new ArrayList<>();
        List<Map<?, ?>> references = new ArrayList<>();
        TransactionContext transaction = proxy(TransactionContext.class, new InvocationHandler() {
            @Override
            public Object invoke(Object object, Method method, Object[] arguments) throws Throwable {
                if (!method.getName().equals("run")) throw new AssertionError(method);
                String query = (String) arguments[0];
                Map<?, ?> parameters = (Map<?, ?>) arguments[1];
                List<Record> records = new ArrayList<>();
                if (query.contains("RETURN e.json")) {
                    String id = (String) parameters.get("id");
                    reads.add(id);
                    if (storedJson.containsKey(id)) {
                        String saved = storedJson.get(id);
                        records.add(jsonRecord(saved));
                    }
                } else if (query.contains("SET e.json")) {
                    storedJson.put((String) parameters.get("id"), (String) parameters.get("json"));
                } else if (query.contains("CREATE (e)-[:WORLD_REF")) {
                    references.add(new HashMap<>(parameters));
                } else if (query.contains("REMOVE e.json")) {
                    storedJson.remove(parameters.get("id"));
                }
                return proxy(Result.class, new InvocationHandler() {
                    @Override
                    public Object invoke(Object result, Method operation, Object[] fields) throws Throwable {
                        if (operation.getName().equals("list")) return records;
                        if (operation.getName().equals("consume")) return null;
                        throw new AssertionError(operation);
                    }
                });
            }
        });
        Session session = proxy(Session.class, new InvocationHandler() {
            @Override
            public Object invoke(Object object, Method method, Object[] arguments) throws Throwable {
                if (method.getName().equals("close")) return null;
                if (method.getName().equals("executeRead") || method.getName().equals("executeWrite")) {
                    return ((TransactionCallback<?>) arguments[0]).execute(transaction);
                }
                throw new AssertionError(method);
            }
        });
        Driver driver = proxy(Driver.class, new InvocationHandler() {
            @Override
            public Object invoke(Object object, Method method, Object[] arguments) throws Throwable {
                if (method.getName().equals("session")) return session;
                throw new AssertionError(method);
            }
        });
        Neo4jEntityStore graph = new Neo4jEntityStore(driver, "neo4j", new EntityJson());
        var first = QdrantEntityStoreCheck.entity(1, false);
        var initial = graph.open("initial", List.of(first));
        initial.prepare(); initial.commit();
        if (!graph.read("a").orElseThrow().equals(first)) throw new AssertionError("完整实体往返");
        if (references.size() != first.bindings().size() + first.evidence().size()) throw new AssertionError("引用投影丢失");
        var update = graph.open("update", List.of(QdrantEntityStoreCheck.entity(2, true)));
        update.prepare(); update.commit(); update.rollback();
        if (!graph.read("a").orElseThrow().equals(first)) throw new AssertionError("快照恢复");
        for (String id : reads) {
            if (!id.equals("a")) {
                throw new AssertionError("不应读取引用目标");
            }
        }
        System.out.println("Neo4j Driver checks passed: full entity/references roundtrip, snapshot restore, no reference expansion");
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }

    // 记录与字段值的替身分开构造，事务逻辑不再嵌套多层代理回调。
    private static Record jsonRecord(String saved) {
        return proxy(Record.class, new InvocationHandler() {
            @Override
            public Object invoke(Object record, Method operation, Object[] fields) {
                if (operation.getName().equals("get") && fields[0].equals("json")) {
                    return jsonValue(saved);
                }
                throw new AssertionError(operation);
            }
        });
    }

    private static Value jsonValue(String saved) {
        return proxy(Value.class, new InvocationHandler() {
            @Override
            public Object invoke(Object value, Method accessor, Object[] ignored) {
                if (accessor.getName().equals("asString")) return saved;
                throw new AssertionError(accessor);
            }
        });
    }
}
