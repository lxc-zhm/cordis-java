package dev.cordis.example.plugins;

import dev.cordis.core.Context;
import dev.cordis.core.Service;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * users 服务:依赖 db(读它持久化数据),由 users 插件注册为服务。
 * 通过已提交视图读 db,所以即使 db 正在撤离,本服务的收尾代码也还能正常用它。
 */
public class UsersService extends Service {

    public final Set<String> users = new LinkedHashSet<>();
    private final DbService db;

    public UsersService(Context ctx, DbService db) {
        super(ctx, "users");
        this.db = db;
    }

    /** 创建一个用户,并写入(可能被隔离的)数据库绑定。 */
    public String create(String name) {
        users.add(name);
        db.put("user:" + name, "active");
        return name;
    }
}
