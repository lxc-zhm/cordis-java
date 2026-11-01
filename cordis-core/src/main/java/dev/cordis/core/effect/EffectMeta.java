package dev.cordis.core.effect;

import java.util.ArrayList;
import java.util.List;

/**
 * 效应元数据:效应名 + 它嵌套登记的子效应,组成一棵诊断树,方便排查问题。
 */
public final class EffectMeta {

    /** 效应的可读名字,例如 ctx.on("event") 或 ctx.provide("name")。 */
    public final String label;
    /** 本效应执行期间嵌套登记的子效应。 */
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
        sb.repeat("  ", depth).append(label).append('\n');
        for (EffectMeta child : children) child.render(sb, depth + 1);
    }
}
