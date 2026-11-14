package ai.neargo.shop.retail.commerce.mapper.impl;

import ai.neargo.shop.retail.commerce.dto.RetailSelection;
import ai.neargo.shop.retail.commerce.mapper.RetailSelectionMapper;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * No mutable copy of product data; publication appends identities then atomically advances the
 * original selection head.
 */
public final class RetailSelectionMapperImpl implements RetailSelectionMapper {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate write;
    private final ObjectMapper json;

    public RetailSelectionMapperImpl(
            JdbcTemplate jdbc, PlatformTransactionManager manager, ObjectMapper json) {
        this.jdbc = jdbc;
        this.write = new TransactionTemplate(manager);
        this.json = json;
    }

    @Override
    public RetailSelection revision(String e, String s, String n, String r) {
        RetailSelection.key(e);
        RetailSelection.key(s);
        RetailSelection.key(n);
        RetailSelection.key(r);
        List<RetailSelection> rows =
                jdbc.query(
                        "SELECT payload_json, content_hash FROM "
                                + "rtl_channel_selection_revision WHERE entity_no=? AND "
                                + "store_no=? AND selection_no=? AND revision_no=?",
                        (rs, i) -> {
                            var value = json.readValue(rs.getString(1), RetailSelection.class);
                            if (!value.contentHash().equals(rs.getString(2))
                                    || !e.equals(value.entityNo())
                                    || !s.equals(value.storeNo())
                                    || !n.equals(value.selectionNo())
                                    || !r.equals(value.revisionNo()))
                                throw new IllegalStateException("Corrupt original selection");
                            return value;
                        },
                        e,
                        s,
                        n,
                        r);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    @Override
    public RetailSelection current(String e, String s, String n) {
        RetailSelection.key(e);
        RetailSelection.key(s);
        RetailSelection.key(n);
        var rows =
                jdbc.queryForList(
                        "SELECT revision_no FROM rtl_channel_selection WHERE entity_no=? AND "
                                + "store_no=? AND selection_no=?",
                        String.class,
                        e,
                        s,
                        n);
        return rows.isEmpty() ? null : revision(e, s, n, rows.getFirst());
    }

    @Override
    public void publish(RetailSelection v, String expected) {
        v.validate();
        if (expected != null) RetailSelection.key(expected);
        write.executeWithoutResult(
                tx -> {
                    jdbc.update(
                            "INSERT INTO"
                                + " rtl_channel_selection_revision(entity_no,store_no,selection_no,revision_no,"
                                + "content_hash,payload_json) VALUES(?,?,?,?,?,?)",
                            v.entityNo(),
                            v.storeNo(),
                            v.selectionNo(),
                            v.revisionNo(),
                            v.contentHash(),
                            json.writeValueAsString(v));
                    if (expected == null)
                        jdbc.update(
                                "INSERT INTO rtl_channel_selection(entity_no,store_no,selection_no,"
                                        + "revision_no) VALUES(?,?,?,?)",
                                v.entityNo(),
                                v.storeNo(),
                                v.selectionNo(),
                                v.revisionNo());
                    else if (jdbc.update(
                                    "UPDATE rtl_channel_selection SET revision_no=? WHERE"
                                            + " entity_no=? AND store_no=? AND selection_no=? AND"
                                            + " revision_no=?",
                                    v.revisionNo(),
                                    v.entityNo(),
                                    v.storeNo(),
                                    v.selectionNo(),
                                    expected)
                            != 1) throw new IllegalStateException("Selection publication conflict");
                });
    }
}
