package com.wxdgaming.batis.sql;

import com.wxdgaming.batis.exception.TypeMismatchDataAccessException;
import com.wxdgaming.batis.model.ColumnInfo;
import com.wxdgaming.batis.model.StorageMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JSON 存储模式在多态字段上的编解码约定测试（fastjson2）。
 * <p>这一档最容易踩的坑是"存进去什么类型、读回来不是"，而它不会报错只会静默退化，
 * 所以两侧行为都要用测试钉住：默认档保持纯业务 JSON（可读回但不带类型），
 * {@code polymorphic = true} 才写类型标记；多态列的读取虽然要开 autoType 特性（嵌套容器只有
 * 在这一档才带得回来），但白名单只认声明类型继承树里的类，指向不相干类的标记必须被拒绝——
 * 那是安全底线，不是可回归的细节。</p>
 * <p>测试直接调基类编解码方法，与具体方言无关（MySQL/pgsql 的 handler 都复用它），
 * 所以修复只需落在一处。</p>
 *
 * @author wxd-gaming
 */
@DisplayName("JSON 存储模式：多态字段的类型标记与 autoType 边界")
class JsonPolymorphicStorageTest {

    /** 只暴露基类编解码能力的桩，避免为了测试拉进整个方言栈 */
    private static final class Codec extends AbstractBeanValueHandler {
        @Override
        public Object convertToDbValue(Object value, ColumnInfo col) {
            return toJsonString(value, col);
        }

        @Override
        public Object convertFromDbValue(Object value, ColumnInfo col) {
            return fromJsonText(value, col);
        }
    }

    private static final Codec CODEC = new Codec();

    public interface Shape {
    }

    public static class Circle implements Shape {
        private double radius;

        public double getRadius() {
            return radius;
        }

        public void setRadius(double radius) {
            this.radius = radius;
        }
    }

    public static class Square implements Shape {
        private double side;

        public double getSide() {
            return side;
        }

        public void setSide(double side) {
            this.side = side;
        }
    }

    public static class Animal {
        private String name;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }

    public static class Dog extends Animal {
        private String breed;

        public String getBreed() {
            return breed;
        }

        public void setBreed(String breed) {
            this.breed = breed;
        }
    }

    /** 与声明类型毫无继承关系的类：用来验证类型标记的拒绝路径 */
    public static class Unrelated {
        /** 计数证明"被拒绝"是真的没被实例化，而不是实例化之后再丢弃 */
        static final java.util.concurrent.atomic.AtomicInteger CREATED =
                new java.util.concurrent.atomic.AtomicInteger();

        private String tag;

        public Unrelated() {
            CREATED.incrementAndGet();
        }

        public String getTag() {
            return tag;
        }

        public void setTag(String tag) {
            this.tag = tag;
        }
    }

    /** 嵌套载体：多态标记会一路写进对象图内部，这里的字段就是"内部还能不能读回来"的探针 */
    public static class Box {
        private Shape inner;
        private List<Shape> shapes = new ArrayList<>();
        private Map<String, Shape> byName = new LinkedHashMap<>();
        private List<String> tags = new ArrayList<>();
        private Number number;

        public Shape getInner() {
            return inner;
        }

        public void setInner(Shape inner) {
            this.inner = inner;
        }

        public List<Shape> getShapes() {
            return shapes;
        }

        public void setShapes(List<Shape> shapes) {
            this.shapes = shapes;
        }

        public Map<String, Shape> getByName() {
            return byName;
        }

        public void setByName(Map<String, Shape> byName) {
            this.byName = byName;
        }

        public List<String> getTags() {
            return tags;
        }

        public void setTags(List<String> tags) {
            this.tags = tags;
        }

        public Number getNumber() {
            return number;
        }

        public void setNumber(Number number) {
            this.number = number;
        }
    }

    /** 白名单类内声明宽泛 JDK 接口字段：expectClass 分支最容易从这里被绕出声明继承树 */
    public static class WithResource implements Shape {
        private AutoCloseable resource;

        public AutoCloseable getResource() {
            return resource;
        }

        public void setResource(AutoCloseable resource) {
            this.resource = resource;
        }
    }

    /** 载体类：字段声明的泛型即 ColumnInfo.genericType 的来源，与 EntityParser 的取法一致 */
    public static class Carrier {
        List<Shape> shapes = new ArrayList<>();
        Map<String, Shape> shapeMap = new LinkedHashMap<>();
        Set<Shape> shapeSet = new LinkedHashSet<>();
        Shape single = new Circle();
        Animal pet = new Dog();
        List<Animal> pets = new ArrayList<>();
        Shape[] arr = new Shape[0];
        Box box = new Box();
        Object any = new Circle();
        Map<String, Object> anyMap = new LinkedHashMap<>();
        Shape resource = new Circle();
        java.io.Serializable blob = "x";
    }

    private static ColumnInfo col(String fieldName, boolean polymorphic) {
        try {
            Field f = Carrier.class.getDeclaredField(fieldName);
            ColumnInfo c = new ColumnInfo();
            c.setColumnName(fieldName);
            c.setFieldName(fieldName);
            c.setJavaType(f.getType());
            c.setGenericType(f.getGenericType());
            c.setStorageMode(StorageMode.JSON);
            c.setNullable(true);
            c.setPolymorphic(polymorphic);
            return c;
        } catch (NoSuchFieldException e) {
            throw new AssertionError(e);
        }
    }

    private static Circle circle(double radius) {
        Circle c = new Circle();
        c.setRadius(radius);
        return c;
    }

    private static Dog dog(String name, String breed) {
        Dog d = new Dog();
        d.setName(name);
        d.setBreed(breed);
        return d;
    }

    private static Box box() {
        Box b = new Box();
        b.setInner(circle(1));
        b.getShapes().add(circle(2));
        b.getByName().put("k", circle(3));
        b.getTags().add("t");
        b.setNumber(7L);
        return b;
    }

    @Test
    @DisplayName("默认档：写出的是不含类型标记的纯业务 JSON")
    void defaultModeWritesNoTypeMarker() {
        String json = (String) CODEC.convertToDbValue(List.of(circle(2), new Square()), col("shapes", false));

        assertFalse(json.contains("@type"), "默认档不该写出类型标记: " + json);
        assertTrue(json.contains("radius"), "业务字段必须在: " + json);
    }

    @Test
    @DisplayName("默认档的退化行为（回归防线）：子类身份静默丢失，不抛异常")
    void defaultModeSilentlyLosesSubtype() {
        ColumnInfo pets = col("pets", false);
        String json = (String) CODEC.convertToDbValue(List.of(dog("rex", "husky")), pets);
        List<?> back = (List<?>) CODEC.convertFromDbValue(json, pets);

        // 声明是 List<Animal>，元素实际是 Dog：默认档读回来只还原到 Animal，子类字段消失
        Object first = back.get(0);
        assertInstanceOf(Animal.class, first);
        assertFalse(first instanceof Dog, "默认档不应还原子类，否则这条回归防线失效");
        assertEquals("rex", ((Animal) first).getName());
    }

    @Test
    @DisplayName("接口字段：默认档读回的是动态代理，polymorphic 才读回实现类")
    void interfaceFieldNeedsPolymorphic() {
        List<Shape> source = List.of(circle(2), new Square());

        List<?> plainBack = (List<?>) CODEC.convertFromDbValue(
                CODEC.convertToDbValue(source, col("shapes", false)), col("shapes", false));
        assertFalse(plainBack.get(0) instanceof Circle, "默认档读回应为接口的动态代理，而非 Circle");

        List<?> typedBack = (List<?>) CODEC.convertFromDbValue(
                CODEC.convertToDbValue(source, col("shapes", true)), col("shapes", true));
        assertInstanceOf(Circle.class, typedBack.get(0));
        assertInstanceOf(Square.class, typedBack.get(1));
        assertEquals(2.0, ((Circle) typedBack.get(0)).getRadius(), 1e-9);
    }

    @Test
    @DisplayName("polymorphic：List/Map/数组/父类字段都能还原运行时类型")
    void polymorphicRestoresRuntimeTypes() {
        ColumnInfo shapeMap = col("shapeMap", true);
        Map<String, Shape> map = new LinkedHashMap<>();
        map.put("c", circle(3));
        Map<?, ?> mapBack = (Map<?, ?>) CODEC.convertFromDbValue(CODEC.convertToDbValue(map, shapeMap), shapeMap);
        assertInstanceOf(Circle.class, mapBack.get("c"));

        ColumnInfo arr = col("arr", true);
        Object[] arrBack = (Object[]) CODEC.convertFromDbValue(
                CODEC.convertToDbValue(new Shape[]{circle(5)}, arr), arr);
        assertInstanceOf(Circle.class, arrBack[0]);

        // 父类字段声明、子类值：根位置必须带标记，否则 breed 会丢
        ColumnInfo pet = col("pet", true);
        Object petBack = CODEC.convertFromDbValue(CODEC.convertToDbValue(dog("rex", "husky"), pet), pet);
        assertInstanceOf(Dog.class, petBack, "父类字段声明下必须读回 Dog");
        assertEquals("husky", ((Dog) petBack).getBreed(), "子类独有字段不能丢");
    }

    @Test
    @DisplayName("polymorphic 会写进对象图内部：嵌套字段同样按声明类型还原")
    void polymorphicAppliesInsideTheObjectGraph() {
        ColumnInfo box = col("box", true);
        Box back = (Box) CODEC.convertFromDbValue(CODEC.convertToDbValue(box(), box), box);

        assertInstanceOf(Circle.class, back.getInner(), "嵌套的接口字段要读回实现类");
        assertInstanceOf(Circle.class, back.getShapes().get(0), "嵌套 List 的元素要读回实现类");
        assertInstanceOf(Circle.class, back.getByName().get("k"), "嵌套 Map 的值要读回实现类");
        assertEquals(List.of("t"), back.getTags());
        assertEquals(7L, back.getNumber().longValue(), "Number 字段不能因为标记变成字符串");
    }

    @Test
    @DisplayName("安全底线：标记指向声明类型之外的类时一律拒绝，连构造都不发生")
    void foreignTypeMarkerIsRejected() {
        String foreign = "[{\"@type\":\"" + Unrelated.class.getName() + "\",\"tag\":\"x\"}]";
        String jdk = "[{\"@type\":\"java.lang.Runtime\"}]";
        // 经典反序列化 gadget：fastjson2 自己的黑名单之外，本框架的白名单也必须挡住
        String denylisted = "[{\"@type\":\"com.sun.rowset.JdbcRowSetImpl\",\"dataSourceName\":\"ldap://x\"}]";

        for (String payload : List.of(foreign, jdk, denylisted)) {
            // 列本身是 polymorphic 的，也不能成为任意类实例化的入口
            TypeMismatchDataAccessException ex = assertThrows(TypeMismatchDataAccessException.class,
                    () -> CODEC.convertFromDbValue(payload, col("shapes", true)),
                    "指向无关类的类型标记必须被拒绝: " + payload);
            assertTrue(ex.getMessage().contains("shapes"), "报错必须点名是哪一列: " + ex.getMessage());
        }
        assertEquals(0, Unrelated.CREATED.get(), "被拒绝的标记不能只是结果丢弃，必须根本没构造过对象");
    }

    @Test
    @DisplayName("JDK 容器标记：容器位置换成标准实现，业务位置照样拒绝")
    void containerMarkersAreNormalizedToStandardImpls() {
        // 这一列本身是 Map：库里点名的容器实现不当真，一律落到 LinkedHashMap，TreeMap/Map.of 都能读回来
        Map<?, ?> mapBack = (Map<?, ?>) CODEC.convertFromDbValue(
                "{\"@type\":\"java.util.TreeMap\",\"c\":{\"@type\":\"" + Circle.class.getName() + "\",\"radius\":1}}",
                col("shapeMap", true));
        assertEquals(LinkedHashMap.class, mapBack.getClass(), "Map 标记必须落到 LinkedHashMap");
        assertInstanceOf(Circle.class, mapBack.get("c"), "值位置的多态不受影响");

        // 业务位置：List<Shape> 的元素写着 PriorityQueue 不是容器，是伪造的类型，必须报错
        assertThrows(TypeMismatchDataAccessException.class,
                () -> CODEC.convertFromDbValue("[{\"@type\":\"java.util.PriorityQueue\"}]", col("shapes", true)),
                "容器实现类不能冒充声明类型");
    }

    @Test
    @DisplayName("对象图内部的非标准容器（TreeMap）也能读回来")
    void nestedExoticContainerIsReadable() {
        Box source = box();
        source.setByName(new java.util.TreeMap<>(source.getByName()));
        ColumnInfo box = col("box", true);

        String json = (String) CODEC.convertToDbValue(source, box);
        Box back = (Box) CODEC.convertFromDbValue(json, box);
        assertInstanceOf(Circle.class, back.getByName().get("k"), "嵌套 Map 的值仍要读回子类: " + json);
    }

    @Test
    @DisplayName("无标记的存量数据照旧可读：开启 polymorphic 不需要回写历史行")
    void legacyRowsWithoutMarkerStillRead() {
        ColumnInfo pets = col("pets", true);
        List<?> back = (List<?>) CODEC.convertFromDbValue("[{\"name\":\"old\"}]", pets);
        assertInstanceOf(Animal.class, back.get(0), "无标记时按声明类型解析，不报错也不猜子类");
        assertEquals("old", ((Animal) back.get(0)).getName());

        // 接口位置没有标记可用：fastjson2 只能给出接口的动态代理，业务字段照读
        List<?> shapes = (List<?>) CODEC.convertFromDbValue("[{\"radius\":8}]", col("shapes", true));
        assertInstanceOf(Shape.class, shapes.get(0), "接口列无标记时读回接口代理: " + shapes.get(0).getClass());
    }

    @Test
    @DisplayName("声明给不出上界的列（Object）多态不生效：两侧都退化成普通列")
    void unboundedDeclarationIsNoOp() {
        ColumnInfo any = col("any", true);
        String json = (String) CODEC.convertToDbValue(circle(2), any);
        assertFalse(json.contains("@type"), "无界声明不该写出无人认领的标记: " + json);

        // 库里若有别的来源写进来的标记，读取端同样不认，按声明类型给出通用容器
        Object back = CODEC.convertFromDbValue(
                "{\"@type\":\"" + Circle.class.getName() + "\",\"radius\":3}", any);
        assertInstanceOf(Map.class, back, "声明为 Object 时按 JSON 对象读回: " + back.getClass());
    }

    @Test
    @DisplayName("Map<String,Object> 无界声明两侧 no-op：值里放业务对象也不写标记、读取不炸")
    void mapOfStringObjectIsUnbounded() {
        ColumnInfo anyMap = col("anyMap", true);
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("c", circle(2));
        String json = (String) CODEC.convertToDbValue(source, anyMap);
        assertFalse(json.contains("@type"), "Map<String,Object> 不该写出自己读不回来的标记: " + json);

        Map<?, ?> back = (Map<?, ?>) CODEC.convertFromDbValue(json, anyMap);
        assertInstanceOf(Map.class, back.get("c"), "无界声明按 JSON 对象读回，不猜子类");
    }

    @Test
    @DisplayName("Set 字段：元素运行时类型可还原；Set 容器标记冒充业务类型照样拒绝")
    void setFieldRoundTripAndMarkerNormalization() {
        ColumnInfo shapeSet = col("shapeSet", true);
        Set<Shape> source = new LinkedHashSet<>();
        source.add(circle(4));
        String json = (String) CODEC.convertToDbValue(source, shapeSet);
        Set<?> back = (Set<?>) CODEC.convertFromDbValue(json, shapeSet);
        assertInstanceOf(Circle.class, back.iterator().next(), "Set 元素要读回实现类: " + json);
        assertInstanceOf(Set.class, back, "读回必须保持 Set 语义，不能退化成 List: " + back.getClass());

        // fastjson2 会把容器标记按元素声明类型分派（expectClass=Shape）：
        // Set 冒充业务类型必须拒绝；归一成 LinkedHashSet 只发生在 expectClass 为 null/Object 或 Set 家族的位置
        assertThrows(TypeMismatchDataAccessException.class,
                () -> CODEC.convertFromDbValue(
                        "{\"@type\":\"java.util.LinkedHashSet\",\"@value\":[{\"@type\":\""
                                + Circle.class.getName() + "\",\"radius\":6}]}", shapeSet),
                "Set 容器标记不能冒充声明类型");
    }

    @Test
    @DisplayName("安全底线：白名单类内的宽泛 JDK 接口字段带不进 JDK gadget")
    void wideJdkInterfaceFieldDoesNotAdmitJdkGadgets() {
        // resource 字段声明为 AutoCloseable：fastjson2 会把 expectClass 带成该接口，
        // JdbcRowSetImpl 恰好实现它——不加 JDK 前缀闸门，这就是绕出继承树的口子
        String payload = "{\"@type\":\"" + WithResource.class.getName()
                + "\",\"resource\":{\"@type\":\"com.sun.rowset.JdbcRowSetImpl\",\"dataSourceName\":\"ldap://x\"}}";
        TypeMismatchDataAccessException ex = assertThrows(TypeMismatchDataAccessException.class,
                () -> CODEC.convertFromDbValue(payload, col("resource", true)),
                "宽泛 JDK 接口字段不能成为 gadget 入口");
        assertTrue(ex.getMessage().contains("resource"), "报错必须点名是哪一列: " + ex.getMessage());
    }

    @Test
    @DisplayName("安全底线：列自身声明为宽泛 JDK 接口（Serializable）时多态不生效，@type 不驱动实例化")
    void jdkInterfaceColumnIsUnbounded() {
        // 根位置声明成 JDK 宽泛接口：collectBounds 必须视同 Object，否则 Serializable 会把
        // JdbcRowSetImpl 放进继承树白名单（经典 autoType RCE）
        ColumnInfo blob = col("blob", true);
        String json = (String) CODEC.convertToDbValue("abc", blob);
        assertFalse(json.contains("@type"), "JDK 接口声明不该写出无人认领的标记: " + json);

        Object back = CODEC.convertFromDbValue(
                "{\"@type\":\"com.sun.rowset.JdbcRowSetImpl\",\"dataSourceName\":\"ldap://x\"}", blob);
        assertFalse(back != null && "com.sun.rowset.JdbcRowSetImpl".equals(back.getClass().getName()),
                "JDK 接口列不能把 @type 当成可实例化的类: " + back);
    }

    @Test
    @DisplayName("polymorphic 只影响序列化特性，不改变列的逻辑类型与空值路径")
    void polymorphicKeepsColumnShape() {        ColumnInfo off = col("shapes", false);
        ColumnInfo on = col("shapes", true);
        assertEquals(off.getSqlType(), on.getSqlType());
        assertEquals(off.getType(), on.getType());
        assertEquals(StorageMode.JSON, on.getStorageMode());
    }

    @Test
    @DisplayName("polymorphic 列仍然是合法 JSON：容器实现与数字不写非标准字面量")
    void polymorphicOutputIsValidJson() {
        String json = (String) CODEC.convertToDbValue(box(), col("box", true));

        assertFalse(json.contains("Set["), "Set 不能写成非标准字面量: " + json);
        assertFalse(json.contains("7L"), "数字不能带类型后缀: " + json);
        assertFalse(json.contains("\"@value\""), "容器不能写成 {\"@type\":...,\"@value\":[...]} 包装: " + json);
        // 合法 JSON：能被严格解析器读回，PG 的原生 json/jsonb 才收得下
        assertInstanceOf(Map.class, com.alibaba.fastjson2.JSON.parseObject(json));
    }
}
