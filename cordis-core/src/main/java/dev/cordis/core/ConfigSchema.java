package dev.cordis.core;

/**
 * 配置校验器:插件启动前对原始配置做检查(和必要的转换)。
 *
 * <p>校验不通过抛 {@link ValidationException},插件保持旧配置继续运行。</p>
 */
@FunctionalInterface
public interface ConfigSchema<T> {

    /**
     * 校验并整理原始配置。
     *
     * @param raw 原始配置
     * @return 整理后的配置,会传给 {@link Plugin#apply}
     * @throws ValidationException 配置不合法时抛出
     */
    T validate(Object raw) throws ValidationException;
}
