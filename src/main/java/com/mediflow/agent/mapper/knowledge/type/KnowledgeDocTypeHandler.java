package com.mediflow.agent.mapper.knowledge.type;

import com.mediflow.agent.knowledge.document.KnowledgeDocType;
import org.apache.ibatis.type.EnumTypeHandler;
import org.apache.ibatis.type.MappedTypes;

import java.sql.CallableStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

@MappedTypes(KnowledgeDocType.class)
public final class KnowledgeDocTypeHandler extends EnumTypeHandler<KnowledgeDocType> {
    public KnowledgeDocTypeHandler() {
        super(KnowledgeDocType.class);
    }

    @Override
    public KnowledgeDocType getNullableResult(ResultSet result, String column) throws SQLException {
        return KnowledgeDocType.from(result.getString(column));
    }

    @Override
    public KnowledgeDocType getNullableResult(ResultSet result, int column) throws SQLException {
        return KnowledgeDocType.from(result.getString(column));
    }

    @Override
    public KnowledgeDocType getNullableResult(CallableStatement statement, int column) throws SQLException {
        return KnowledgeDocType.from(statement.getString(column));
    }
}
