package com.jarvis.research.tools;

import org.flywaydb.core.Flyway;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 一次性 H2 -> PostgreSQL 数据迁移工具。
 *
 * 安全约束：
 * 1. 源 H2 只读打开；
 * 2. 目标业务表必须为空；
 * 3. 目标 schema 必须先由 Flyway 创建；
 * 4. 每表复制后核对行数；
 * 5. 任意失败会回滚 PostgreSQL 事务。
 */
public final class H2ToPostgresMigration {

    private static final String CONFIRM_VALUE = "MIGRATE_H2_TO_POSTGRES";
    private static final int BATCH_SIZE = 1000;

    private record TableSpec(String name, List<String> columns, boolean optional) {}

    private static final List<TableSpec> TABLES = List.of(
            new TableSpec("users", List.of(
                    "id", "email", "password_hash", "display_name", "enabled", "created_at"), false),
            new TableSpec("sim_account", List.of(
                    "id", "user_id", "initial_cash", "cash", "loan_balance", "frozen_margin", "status", "created_at"), false),
            new TableSpec("sim_position", List.of(
                    "id", "user_id", "symbol", "quantity", "avg_cost", "leverage", "loan_amount", "margin_used", "updated_at"), false),
            new TableSpec("sim_trade", List.of(
                    "id", "user_id", "symbol", "type", "client_order_id", "price", "quantity", "amount", "leverage", "created_at"), false),
            new TableSpec("price_snapshot", List.of(
                    "id", "market", "price", "change", "change_pct", "prev_close", "open", "high", "low", "ts"), false),
            new TableSpec("kline_daily", List.of(
                    "id", "market", "date", "open", "close", "high", "low", "volume"), false),
            new TableSpec("audit_event", List.of(
                    "id", "user_id", "action", "target", "client_ip", "detail", "created_at"), true)
    );

    private H2ToPostgresMigration() {}

    public static void main(String[] args) throws Exception {
        requireConfirmation();

        String h2Url = env("H2_SOURCE_URL",
                "jdbc:h2:file:./data/research;MODE=MySQL;ACCESS_MODE_DATA=r");
        String h2User = env("H2_SOURCE_USERNAME", "sa");
        String h2Password = env("H2_SOURCE_PASSWORD", "");
        String pgUrl = requiredEnv("DB_URL");
        String pgUser = requiredEnv("DB_USERNAME");
        String pgPassword = requiredEnv("DB_PASSWORD");

        if (!pgUrl.startsWith("jdbc:postgresql:")) {
            throw new IllegalArgumentException("DB_URL 必须是 jdbc:postgresql: URL");
        }
        if (!h2Url.startsWith("jdbc:h2:")) {
            throw new IllegalArgumentException("H2_SOURCE_URL 必须是 jdbc:h2: URL");
        }

        Class.forName("org.h2.Driver");
        Class.forName("org.postgresql.Driver");

        System.out.println("[migration] source=" + redactUrl(h2Url));
        System.out.println("[migration] target=" + redactUrl(pgUrl));
        migrateTargetSchema(pgUrl, pgUser, pgPassword);

        try (Connection source = DriverManager.getConnection(h2Url, h2User, h2Password);
             Connection target = DriverManager.getConnection(pgUrl, pgUser, pgPassword)) {
            source.setReadOnly(true);
            target.setAutoCommit(false);

            try {
                verifyTargetIsEmpty(target);
                for (TableSpec table : TABLES) {
                    migrateTable(source, target, table);
                }
                resetSequences(target);
                target.commit();
                System.out.println("[migration] SUCCESS - PostgreSQL transaction committed");
            } catch (Exception e) {
                target.rollback();
                System.err.println("[migration] FAILED - PostgreSQL transaction rolled back: " + e.getMessage());
                throw e;
            }
        }
    }

    private static void migrateTargetSchema(String pgUrl, String pgUser, String pgPassword) {
        System.out.println("[migration] applying Flyway schema migrations...");
        Flyway.configure()
                .dataSource(pgUrl, pgUser, pgPassword)
                .locations("classpath:db/migration")
                .baselineOnMigrate(false)
                .load()
                .migrate();
    }

    private static void verifyTargetIsEmpty(Connection target) throws SQLException {
        for (TableSpec table : TABLES) {
            if (!tableExists(target, table.name())) {
                if (table.optional()) continue;
                throw new IllegalStateException("目标表不存在: " + table.name() + "，请先运行 Flyway");
            }
            long count = countRows(target, table.name());
            if (count != 0) {
                throw new IllegalStateException("目标表非空，拒绝迁移: " + table.name() + " rows=" + count);
            }
        }
    }

    private static void migrateTable(Connection source, Connection target, TableSpec table) throws SQLException {
        if (!tableExists(source, table.name())) {
            if (table.optional()) {
                System.out.println("[migration] skip optional source table: " + table.name());
                return;
            }
            throw new IllegalStateException("源表不存在: " + table.name());
        }
        if (!tableExists(target, table.name())) {
            if (table.optional()) {
                System.out.println("[migration] skip optional target table: " + table.name());
                return;
            }
            throw new IllegalStateException("目标表不存在: " + table.name());
        }

        Set<String> sourceColumns = columns(source, table.name());
        Set<String> targetColumns = columns(target, table.name());
        List<String> common = table.columns().stream()
                .filter(sourceColumns::contains)
                .filter(targetColumns::contains)
                .toList();

        if (!common.contains("id")) {
            throw new IllegalStateException(table.name() + " 缺少 id 列");
        }

        long sourceCount = countRows(source, table.name());
        if (sourceCount == 0) {
            System.out.println("[migration] " + table.name() + ": 0 rows");
            return;
        }

        String columnCsv = String.join(",", common);
        String placeholders = String.join(",", common.stream().map(c -> "?").toList());
        String selectSql = "SELECT " + columnCsv + " FROM " + table.name() + " ORDER BY id";
        String insertSql = "INSERT INTO " + table.name() + " (" + columnCsv + ") VALUES (" + placeholders + ")";

        long copied = 0;
        try (PreparedStatement read = source.prepareStatement(selectSql);
             PreparedStatement write = target.prepareStatement(insertSql)) {
            read.setFetchSize(BATCH_SIZE);
            try (ResultSet rs = read.executeQuery()) {
                int pending = 0;
                while (rs.next()) {
                    for (int i = 0; i < common.size(); i++) {
                        write.setObject(i + 1, rs.getObject(i + 1));
                    }
                    write.addBatch();
                    pending++;
                    copied++;
                    if (pending >= BATCH_SIZE) {
                        write.executeBatch();
                        pending = 0;
                    }
                }
                if (pending > 0) write.executeBatch();
            }
        }

        long targetCount = countRows(target, table.name());
        if (copied != sourceCount || targetCount != sourceCount) {
            throw new IllegalStateException(table.name() + " 行数校验失败: source=" + sourceCount
                    + ", copied=" + copied + ", target=" + targetCount);
        }
        System.out.println("[migration] " + table.name() + ": " + copied + " rows OK");
    }

    private static void resetSequences(Connection target) throws SQLException {
        for (TableSpec table : TABLES) {
            if (!tableExists(target, table.name())) continue;
            String sql = "SELECT setval(pg_get_serial_sequence('" + table.name() + "','id'), "
                    + "COALESCE(MAX(id), 1), MAX(id) IS NOT NULL) FROM " + table.name();
            try (Statement st = target.createStatement()) {
                st.execute(sql);
            }
        }
    }

    private static boolean tableExists(Connection conn, String table) throws SQLException {
        DatabaseMetaData meta = conn.getMetaData();
        for (String candidate : List.of(table, table.toUpperCase(Locale.ROOT), table.toLowerCase(Locale.ROOT))) {
            try (ResultSet rs = meta.getTables(null, null, candidate, new String[]{"TABLE"})) {
                if (rs.next()) return true;
            }
        }
        return false;
    }

    private static Set<String> columns(Connection conn, String table) throws SQLException {
        Set<String> out = new LinkedHashSet<>();
        String sql = "SELECT * FROM " + table + " WHERE 1=0";
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            ResultSetMetaData meta = rs.getMetaData();
            for (int i = 1; i <= meta.getColumnCount(); i++) {
                out.add(meta.getColumnLabel(i).toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }

    private static long countRows(Connection conn, String table) throws SQLException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static void requireConfirmation() {
        if (!CONFIRM_VALUE.equals(System.getenv("MIGRATION_CONFIRM"))) {
            throw new IllegalStateException("安全保护：请设置 MIGRATION_CONFIRM=" + CONFIRM_VALUE);
        }
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("缺少环境变量: " + name);
        }
        return value;
    }

    private static String env(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null ? defaultValue : value;
    }

    private static String redactUrl(String url) {
        int query = url.indexOf('?');
        return query >= 0 ? url.substring(0, query) + "?..." : url;
    }
}
