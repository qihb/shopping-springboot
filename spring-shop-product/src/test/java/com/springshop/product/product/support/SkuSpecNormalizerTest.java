package com.springshop.product.product.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * 规格规范化单测
 *
 * <p>这些断言就是「唯一性规则能否成立」的底线：任意一组「人眼相同」的写法
 * 必须产出同一个键，否则查重会被一个空格或一个全角符号绕过。
 */
class SkuSpecNormalizerTest {

    @Test
    void normalize_shouldTreatNullAndBlankAsSameEmptyKey() {
        assertEquals("", SkuSpecNormalizer.normalize(null));
        assertEquals("", SkuSpecNormalizer.normalize(""));
        assertEquals("", SkuSpecNormalizer.normalize("   "));
        assertEquals("", SkuSpecNormalizer.normalize("；,、 "), "只有分隔符与空白 ⇒ 等同于空规格");
        assertEquals(SkuSpecNormalizer.normalize(null), SkuSpecNormalizer.normalize("  "));
    }

    @Test
    void normalize_shouldTrimOuterWhitespace() {
        assertEquals("颜色:黑", SkuSpecNormalizer.normalize("  颜色:黑  "));
    }

    @Test
    void normalize_shouldIgnoreWhitespaceInsideSegment() {
        // 段内空格/全角空格都不该让两条规格变成不同商品
        assertEquals(SkuSpecNormalizer.normalize("颜色:黑"), SkuSpecNormalizer.normalize("颜色: 黑"));
        assertEquals(SkuSpecNormalizer.normalize("颜色:黑"), SkuSpecNormalizer.normalize("颜色:\u3000黑"));
        assertEquals(SkuSpecNormalizer.normalize("尺寸:L"), SkuSpecNormalizer.normalize("尺寸 : L"));
    }

    @Test
    void normalize_shouldUnifyFullWidthColon() {
        assertEquals(SkuSpecNormalizer.normalize("颜色:黑"), SkuSpecNormalizer.normalize("颜色：黑"));
    }

    @Test
    void normalize_shouldUnifyAllSeparatorVariants() {
        String expected = SkuSpecNormalizer.normalize("颜色:黑;尺寸:L");
        assertEquals(expected, SkuSpecNormalizer.normalize("颜色:黑；尺寸:L"), "全角分号");
        assertEquals(expected, SkuSpecNormalizer.normalize("颜色:黑,尺寸:L"), "半角逗号");
        assertEquals(expected, SkuSpecNormalizer.normalize("颜色:黑，尺寸:L"), "全角逗号");
        assertEquals(expected, SkuSpecNormalizer.normalize("颜色:黑、尺寸:L"), "顿号");
    }

    @Test
    void normalize_shouldSortSegments() {
        // 段序不同必须等价 —— 这是「按段排序」存在的唯一理由
        assertEquals(SkuSpecNormalizer.normalize("颜色:黑;尺寸:L"),
                SkuSpecNormalizer.normalize("尺寸:L;颜色:黑"));
    }

    @Test
    void normalize_shouldDropEmptySegments() {
        assertEquals(SkuSpecNormalizer.normalize("颜色:黑"), SkuSpecNormalizer.normalize(";颜色:黑;;"));
        assertEquals(SkuSpecNormalizer.normalize("颜色:黑;尺寸:L"),
                SkuSpecNormalizer.normalize("颜色:黑; ;尺寸:L;"));
    }

    @Test
    void normalize_shouldKeepDifferentValuesApart() {
        // 反向断言：规范化不能把真正不同的规格合并，否则会误拒合法新增
        assertNotEquals(SkuSpecNormalizer.normalize("颜色:黑"), SkuSpecNormalizer.normalize("颜色:白"));
        assertNotEquals(SkuSpecNormalizer.normalize("颜色:黑"), SkuSpecNormalizer.normalize("颜色:黑;尺寸:L"));
        assertNotEquals(SkuSpecNormalizer.normalize("颜色:黑"), SkuSpecNormalizer.normalize("颜色:黑色"));
        assertNotEquals(SkuSpecNormalizer.normalize("颜色:黑"), SkuSpecNormalizer.normalize("颜色:黑X"));
        // 不做大小写折叠：A 与 a 视为不同（规格以中文为主，折叠收益低于误合并风险）
        assertNotEquals(SkuSpecNormalizer.normalize("Size:A"), SkuSpecNormalizer.normalize("Size:a"));
    }

    @Test
    void normalize_shouldBeIdempotent() {
        // 已是规范形再规范化必须不变，否则「查重键」与「落库后回读再算的键」会漂移
        String once = SkuSpecNormalizer.normalize("尺寸:L；颜色： 黑");
        assertEquals(once, SkuSpecNormalizer.normalize(once));
    }
}
