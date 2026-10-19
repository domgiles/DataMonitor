package com.dom.datamonitor.db;

import java.lang.reflect.Proxy;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.List;

/**
 * Builds an in-memory {@link ResultSet} via dynamic proxies, supporting only the calls
 * {@link QueryResultParser} makes: next, getString, getObject, getMetaData,
 * and on the metadata getColumnCount / getColumnType.
 */
final class FakeResultSet {

    private FakeResultSet() {}

    /**
     * @param sqlTypes JDBC {@link java.sql.Types} constant per column
     * @param rows     row values; each array must have {@code sqlTypes.length} entries
     */
    static ResultSet of(int[] sqlTypes, List<Object[]> rows) {
        ResultSetMetaData meta = (ResultSetMetaData) Proxy.newProxyInstance(
            FakeResultSet.class.getClassLoader(), new Class<?>[]{ResultSetMetaData.class},
            (p, m, a) -> switch (m.getName()) {
                case "getColumnCount" -> sqlTypes.length;
                case "getColumnType"  -> sqlTypes[(Integer) a[0] - 1];
                default -> throw new UnsupportedOperationException(m.getName());
            });

        int[] cursor = {-1};
        return (ResultSet) Proxy.newProxyInstance(
            FakeResultSet.class.getClassLoader(), new Class<?>[]{ResultSet.class},
            (p, m, a) -> switch (m.getName()) {
                case "getMetaData" -> meta;
                case "next"        -> ++cursor[0] < rows.size();
                case "getObject"   -> rows.get(cursor[0])[(Integer) a[0] - 1];
                case "getString"   -> {
                    Object v = rows.get(cursor[0])[(Integer) a[0] - 1];
                    yield v == null ? null : v.toString();
                }
                default -> throw new UnsupportedOperationException(m.getName());
            });
    }
}
