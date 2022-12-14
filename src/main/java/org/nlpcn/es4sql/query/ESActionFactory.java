package org.nlpcn.es4sql.query;

import com.alibaba.druid.sql.ast.SQLExpr;
import com.alibaba.druid.sql.ast.expr.SQLQueryExpr;
import com.alibaba.druid.sql.ast.statement.SQLDeleteStatement;
import com.alibaba.druid.sql.ast.statement.SQLJoinTableSource;
import com.alibaba.druid.sql.ast.statement.SQLSelectQueryBlock;
import com.alibaba.druid.sql.ast.statement.SQLUnionQuery;
import com.alibaba.druid.sql.dialect.mysql.parser.MySqlStatementParser;
import com.alibaba.druid.sql.parser.ParserException;
import com.alibaba.druid.sql.parser.SQLExprParser;
import com.alibaba.druid.sql.parser.SQLStatementParser;
import com.alibaba.druid.sql.parser.Token;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.Client;
import org.elasticsearch.plugin.nlpcn.ElasticResultHandler;
import org.elasticsearch.plugin.nlpcn.QueryActionElasticExecutor;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.SearchHits;
import org.nlpcn.es4sql.domain.Delete;
import org.nlpcn.es4sql.domain.JoinSelect;
import org.nlpcn.es4sql.domain.Select;
import org.nlpcn.es4sql.exception.SqlParseException;
import org.nlpcn.es4sql.parse.ElasticLexer;
import org.nlpcn.es4sql.parse.ElasticSqlExprParser;
import org.nlpcn.es4sql.parse.SqlParser;
import org.nlpcn.es4sql.parse.SubQueryExpression;
import org.nlpcn.es4sql.query.join.ESJoinQueryActionFactory;
import org.nlpcn.es4sql.query.multi.MultiQueryAction;
import org.nlpcn.es4sql.query.multi.MultiQuerySelect;

import java.sql.SQLFeatureNotSupportedException;
import java.util.ArrayList;
import java.util.List;

public class ESActionFactory {

	/**
	 * Create the compatible Query object
	 * based on the SQL query.
	 * @param sql The SQL query.
	 * @return Query object.
	 */
    public static QueryAction create(Client client, String sql) throws SqlParseException, SQLFeatureNotSupportedException {
        sql = sql.replaceAll("\n", " ").trim();
        String firstWord = sql.substring(0, sql.indexOf(' '));
        switch (firstWord.toUpperCase()) {
			case "SELECT":
			    //zhongshu-comment ???sql??????????????????AST??????SQLQueryExpr sqlExpr??????AST????????????????????????????????????AST???????????????token
				SQLQueryExpr sqlExpr = (SQLQueryExpr) toSqlExpr(sql);
                if(isMulti(sqlExpr)){//zhongshu-comment ???????????????union?????????union????????????select?????????btw????????????????????????select???????????????2???
                    MultiQuerySelect multiSelect = new SqlParser().parseMultiSelect((SQLUnionQuery) sqlExpr.getSubQuery().getQuery());
                    handleSubQueries(client,multiSelect.getFirstSelect());
                    handleSubQueries(client,multiSelect.getSecondSelect());
                    return new MultiQueryAction(client, multiSelect);
                }
                else if(isJoin(sqlExpr,sql)){//zhongshu-comment join????????????
                    JoinSelect joinSelect = new SqlParser().parseJoinSelect(sqlExpr);
                    handleSubQueries(client, joinSelect.getFirstTable());
                    handleSubQueries(client, joinSelect.getSecondTable());
                    return ESJoinQueryActionFactory.createJoinAction(client, joinSelect);
                }
                else {
                    //zhongshu-comment ????????????????????????????????????????????????????????????
                    Select select = new SqlParser().parseSelect(sqlExpr);
                    //todo ???????????????????????????????????????sql??????????????????handleSubQueries??????????????????????????????????????????
                    handleSubQueries(client, select);
                    return handleSelect(client, select);
                }
			case "DELETE":
                SQLStatementParser parser = createSqlStatementParser(sql);
				SQLDeleteStatement deleteStatement = parser.parseDeleteStatement();
				Delete delete = new SqlParser().parseDelete(deleteStatement);
				return new DeleteQueryAction(client, delete);
            case "SHOW":
                return new ShowQueryAction(client,sql);
			default:
				throw new SQLFeatureNotSupportedException(String.format("Unsupported query: %s", sql));
		}
	}

    private static boolean isMulti(SQLQueryExpr sqlExpr) {
        return sqlExpr.getSubQuery().getQuery() instanceof SQLUnionQuery;
    }

    private static void handleSubQueries(Client client, Select select) throws SqlParseException {
        if (select.containsSubQueries())
        {
            for(SubQueryExpression subQueryExpression : select.getSubQueries()){
                QueryAction queryAction = handleSelect(client, subQueryExpression.getSelect());
                executeAndFillSubQuery(client , subQueryExpression,queryAction);
            }
        }
    }

    private static void executeAndFillSubQuery(Client client , SubQueryExpression subQueryExpression,QueryAction queryAction) throws SqlParseException {
        List<Object> values = new ArrayList<>();
        Object queryResult;
        try {
            queryResult = QueryActionElasticExecutor.executeAnyAction(client,queryAction);
        } catch (Exception e) {
            throw new SqlParseException("could not execute SubQuery: " +  e.getMessage());
        }

        String returnField = subQueryExpression.getReturnField();
        if(queryResult instanceof SearchHits) {
            SearchHits hits = (SearchHits) queryResult;
            for (SearchHit hit : hits) {
                values.add(ElasticResultHandler.getFieldValue(hit,returnField));
            }
        } else if (queryResult instanceof SearchResponse) {
            SearchHits hits = ((SearchResponse) queryResult).getHits();
            for (SearchHit hit : hits) {
                values.add(ElasticResultHandler.getFieldValue(hit, returnField));
            }
        } else {
            throw new SqlParseException("on sub queries only support queries that return Hits and not aggregations");
        }
        subQueryExpression.setValues(values.toArray());
    }

    private static QueryAction handleSelect(Client client, Select select) {
        if (select.isAgg) {
            return new AggregationQueryAction(client, select);
        } else {
            return new DefaultQueryAction(client, select);
        }
    }

    public static SQLStatementParser createSqlStatementParser(String sql) {
        ElasticLexer lexer = new ElasticLexer(sql);
        lexer.nextToken();
        return new MySqlStatementParser(lexer);
    }

    private static boolean isJoin(SQLQueryExpr sqlExpr,String sql) {
        SQLSelectQueryBlock query = (SQLSelectQueryBlock) sqlExpr.getSubQuery().getQuery();
        return query.getFrom() instanceof SQLJoinTableSource && ((SQLJoinTableSource) query.getFrom()).getJoinType() != SQLJoinTableSource.JoinType.COMMA && sql.toLowerCase().contains("join");
    }

    private static SQLExpr toSqlExpr(String sql) {
        SQLExprParser parser = new ElasticSqlExprParser(sql); //zhongshu-comment ??????SQLExprParser parser???????????????????????????
        SQLExpr expr = parser.expr(); //zhongshu-comment ??????expr??????????????????sql???????????????AST???

        //zhongshu-comment ??????parser.expr()???????????????sql??????????????????????????????token??????End Of File???????????????sql?????????????????????????????????????????????????????????????????????sql
        if (parser.getLexer().token() != Token.EOF) {
            throw new ParserException("illegal sql expr : " + sql);
        }

        return expr;
    }



}
