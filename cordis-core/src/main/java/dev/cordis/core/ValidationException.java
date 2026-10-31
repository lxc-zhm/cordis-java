package dev.cordis.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 配置校验异常:插件配置未通过 {@link ConfigSchema} 校验时抛出,
 * 携带每条问题(出错信息 + 出错位置)。
 */
public class ValidationException extends CordisException {

    /** 一条校验问题:出错信息 + 出错位置。 */
    public record Issue(String message, List<String> path) {
        public Issue(String message) {
            this(message, List.of());
        }

        @Override
        public String toString() {
            return path.isEmpty() ? message : message + " (at " + String.join(".", path) + ")";
        }
    }

    private final List<Issue> issues;

    public ValidationException(List<Issue> issues) {
        super(Code.INVALID_PLUGIN, "invalid config:\n  - " + String.join("\n  - ",
                issues.stream().map(Issue::toString).toList()));
        this.issues = new ArrayList<>(issues);
    }

    public List<Issue> getIssues() {
        return Collections.unmodifiableList(issues);
    }
}
