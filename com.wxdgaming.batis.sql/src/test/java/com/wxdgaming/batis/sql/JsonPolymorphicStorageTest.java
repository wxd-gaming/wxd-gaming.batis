package com.wxdgaming.batis.sql;

import com.wxdgaming.batis.exception.TypeMismatchDataAccessException;
import com.wxdgaming.batis.model.ColumnInfo;
import com.wxdgaming.batis.model.StorageMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JSON 存储模式在多态字段上的编解码约定测试。
 * <p>这一档最容易踩的坑是"存进去什么类型、读回来不是"，而它不会报错只会静默退化，
 * 所以两侧行为都要用测试钉住：默认档保持纯业务 JSON（可读回但不带类型），
 * {@code polymorphic = true} 才写类型标记；同时读取端始终不开 autoType，
 * 指向不相干类的类型标记必须被拒绝——那是安全底线，不是可回归的细节。</p>
 * <p>测试直接调基类的编解码方法，与具体方言无关（MySQL/pgsql 的 handler 都复用它），
 * 因此修复只需落在一处。</p>
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
        private String tag;

        public String getTag() {
            return tag;
        }

        public void setTag(String tag) {
            this.tag = tag;
        }
    }

    /** 载体类：字段声明的泛型即 ColumnInfo.genericType 的来源，与 EntityParser 的取法一致 */
    public static class Carrier {
        List<Shape> shapes = new ArrayList<>();
        Map<String, Shape> shapeMap = new LinkedHashMap<>();
        Shape single = new Circle();
        Animal pet = new Dog();
        List<Animal> pets = new ArrayList<>();
        Object any = new Circle();
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

    @Test
    @DisplayName("默认档：写出的是不含类型标记的纯业务 JSON")
    void defaultModeWritesNoTypeMarker() {
        ColumnInfo shapes = col("shapes", false);
        String json = (String) CODEC.convertToDbValue(List.of(circle(2), new Square()), shapes);

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
    @DisplayName("接口字段：默认档读回的不是实现类，polymorphic 才是")
    void interfaceFieldNeedsPolymorphic() {
        List<Shape> source = List.of(circle(2), new Square());

        Object plain = CODEC.convertFromDbValue(
                CODEC.convertToDbValue(source, col("shapes", false)), col("shapes", false));
        List<?> plainBack = (List<?>) plain;
        assertFalse(plainBack.get(0) instanceof Circle, "默认档读回应为接口的动态代理，而非 Circle");

        Object typed = CODEC.convertFromDbValue(
                CODEC.convertToDbValue(source, col("shapes", true)), col("shapes", true));
        List<?> typedBack = (List<?>) typed;
        assertInstanceOf(Circle.class, typedBack.get(0));
        assertInstanceOf(Square.class, typedBack.get(1));
        assertEquals(2.0, ((Circle) typedBack.get(0)).getRadius(), 1e-9);
    }

    @Test
    @DisplayName("polymorphic：Map 值与父类字段都能还原运行时类型")
    void polymorphicRestoresRuntimeTypes() {
        ColumnInfo shapeMap = col("shapeMap", true);
        Map<String, Shape> map = new LinkedHashMap<>();
        map.put("c", circle(3));
        Object mapBack = CODEC.convertFromDbValue(CODEC.convertToDbValue(map, shapeMap), shapeMap);
        assertInstanceOf(Circle.class, ((Map<?, ?>) mapBack).get("c"));

        ColumnInfo pet = col("pet", true);
        Dog rex = dog("rex", "husky");
        Object petBack = CODEC.convertFromDbValue(CODEC.convertToDbValue(rex, pet), pet);
        assertInstanceOf(Dog.class, petBack, "父类字段声明下必须读回 Dog");
        assertEquals("husky", ((Dog) petBack).getBreed(), "子类独有字段不能丢");
    }

    @Test
    @DisplayName("安全底线：类型标记指向声明类型的无关类时一律拒绝（不开 autoType）")
    void foreignTypeMarkerIsRejected() {
        String foreign = "[{\"@type\":\"" + Unrelated.class.getName() + "\",\"tag\":\"x\"}]";
        String gadget = "[{\"@type\":\"java.util.PriorityQueue\"}]";

        for (String payload : List.of(foreign, gadget)) {
            // 列本身是 polymorphic 的，也不能成为任意类实例化的入口
            TypeMismatchDataAccessException ex = assertThrows(TypeMismatchDataAccessException.class,
                    () -> CODEC.convertFromDbValue(payload, col("shapes", true)),
                    "指向无关类的类型标记必须被拒绝: " + payload);
            assertNotNull(ex.getMessage());
        }
    }

    @Test
    @DisplayName("边界：声明为 Object 的列只保证写侧，读回结果不作为契约")
    void objectDeclaredFieldOnlyWritesMarker() {
        // 同一份 Object 列文本，在不同执行顺序下读到过 Circle，也抛过反序列化失败：
        // 期望类型为 Object 时 fastjson 是否放行标记取决于它内部的已注册类缓存。
        // 因此这里只钉"polymorphic 确实写出了标记"，读回行为留给强类型列的用例覆盖。
        String json = (String) CODEC.convertToDbValue(circle(2), col("any", true));
        assertTrue(json.contains("@type"), "polymorphic 列必须写出类型标记: " + json);
    }

    @Test
    @DisplayName("polymorphic 只影响序列化特性，不改变列的逻辑类型与空值路径")
    void polymorphicKeepsColumnShape() {
        // null 由方言 handler 在最前面短路，基类编解码收到的永远是非空值；
        // 这里钉住的是"开启标记不会把列变成别的类型"
        ColumnInfo off = col("shapes", false);
        ColumnInfo on = col("shapes", true);
        assertEquals(off.getSqlType(), on.getSqlType());
        assertEquals(off.getType(), on.getType());
        assertEquals(StorageMode.JSON, on.getStorageMode());
    }

}
