package dev.cordis.core.effect;

import java.util.ArrayList;
import java.util.List;

/**
 * 效果的诊断信息:效果名 + 它内部嵌套登记的子效果,组成一棵树,方便排查问题。
 */
public final class EffectMeta {

    /** 效果的可读名字,例如 ctx.on("event") 或 ctx.provide("name")。 */
    public final String label;
    /** 本效果执行期间嵌套登记的子效果。 */
    public final List<EffectMeta> children = new ArrayList<>();

    public EffectMeta(String label) {
        this.label = label;
    }

    /** 把这棵树打印成缩进文本,方便排查。 */
    public String render() {
        StringBuilder sb = new StringBuilder();
        render(sb, 0);
        return sb.toString();
    }

    private void render(StringBuilder sb, int depth) {
        sb.append("  ".repeat(depth)).append(label).append('\n');
        for (EffectMeta child : children) child.render(sb, depth + 1);
    }
}
