package com.springshop.product.product.support;

import java.util.ArrayList;
import java.util.List;

/**
 * SKU 规格字符串规范化 —— 商品唯一性判定的「比较键」
 *
 * <p><b>为什么需要它</b>：商品唯一性单元是 {@code (商品名称, 规格)}（SKU 粒度），
 * 而 {@code product_sku.specs} 是<b>自由文本</b>（如 {@code 颜色:黑;尺寸:L}）。
 * 若直接拿原文比对，下面这些「人眼完全一样」的写法都会被当成不同商品，
 * 唯一性规则形同虚设：
 *
 * <pre>
 *   颜色:黑;尺寸:L   vs  尺寸:L;颜色:黑     —— 段序不同
 *   颜色:黑          vs  颜色：黑           —— 全角冒号
 *   颜色:黑;尺寸:L   vs  颜色:黑；尺寸:L    —— 全角分号
 *   颜色:黑          vs  颜色: 黑           —— 段内空格
 *   (null)           vs  (空串)             —— 可空列
 * </pre>
 *
 * <p><b>规范化步骤</b>（顺序固定，保证同一输入永远得到同一键）：
 * <ol>
 *   <li>{@code null} / 全空白 → 空串（<b>不能留 null</b>，否则后续比较失效）；</li>
 *   <li>按分隔符切段：{@code ;} {@code ；} {@code ,} {@code ，} {@code 、} 一律视为分隔；</li>
 *   <li>每段去掉<b>全部空白</b>（含段内空格与全角空格），并把全角冒号 {@code ：} 归一为 {@code :}；</li>
 *   <li>丢掉空段；</li>
 *   <li>按段<b>排序</b>后用 {@code ;} 重新拼接。</li>
 * </ol>
 *
 * <p><b>刻意不做的事</b>：
 * <ul>
 *   <li>不做大小写折叠 —— 规格以中文为主，折叠反而会让 {@code A} 与 {@code a} 这类
 *       本该区分的编码意外合并；</li>
 *   <li>不做全角字母/数字转换 —— 保持「只处理分隔与空白」这条可解释的边界，
 *       避免用 NFKC 这种覆盖面很广的规则带来难以预期的合并。</li>
 * </ul>
 *
 * <p>本类<b>只用于比较</b>：落库的 {@code product_sku.specs} 始终保留运营填写的原文，
 * 保证展示不受规范化影响。
 */
public final class SkuSpecNormalizer {

    /** 规范化后统一使用的分隔符 */
    private static final String CANONICAL_SEPARATOR = ";";

    /** 等价分隔符：半角/全角分号、半角/全角逗号、顿号 */
    private static final String SEPARATOR_REGEX = "[;；,，、]";

    private static final char FULL_WIDTH_COLON = '：';

    private static final char HALF_WIDTH_COLON = ':';

    private SkuSpecNormalizer() {
    }

    /**
     * 生成规格的比较键
     *
     * @param specs 运营填写的规格原文，可为 {@code null}
     * @return 规范化后的键；{@code null} 与空串都归一为 {@code ""}（两者相等）
     */
    public static String normalize(String specs) {
        if (specs == null || specs.isEmpty()) {
            return "";
        }
        String[] segments = specs.split(SEPARATOR_REGEX);
        List<String> cleaned = new ArrayList<>(segments.length);
        for (String segment : segments) {
            String value = cleanSegment(segment);
            if (!value.isEmpty()) {
                cleaned.add(value);
            }
        }
        if (cleaned.isEmpty()) {
            return "";
        }
        // 排序让「段序不同」等价：颜色:黑;尺寸:L == 尺寸:L;颜色:黑
        cleaned.sort(null);
        return String.join(CANONICAL_SEPARATOR, cleaned);
    }

    /**
     * 段内清洗：去掉全部空白（含段内空格与全角空格），全角冒号归一为半角
     */
    private static String cleanSegment(String segment) {
        StringBuilder builder = new StringBuilder(segment.length());
        for (int i = 0; i < segment.length(); i++) {
            char c = segment.charAt(i);
            // isWhitespace 不含不换行空格，isSpaceChar 不含制表/换行，两者取并集才不会漏
            if (Character.isWhitespace(c) || Character.isSpaceChar(c)) {
                continue;
            }
            builder.append(c == FULL_WIDTH_COLON ? HALF_WIDTH_COLON : c);
        }
        return builder.toString();
    }
}
