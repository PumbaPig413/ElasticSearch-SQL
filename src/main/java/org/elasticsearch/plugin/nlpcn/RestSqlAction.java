package org.elasticsearch.plugin.nlpcn;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.client.node.NodeClient;
import org.elasticsearch.xcontent.XContentParser;
import org.elasticsearch.xcontent.XContentType;
import org.elasticsearch.plugin.nlpcn.executors.ActionRequestRestExecuterFactory;
import org.elasticsearch.plugin.nlpcn.executors.RestExecutor;
import org.elasticsearch.rest.BaseRestHandler;
import org.elasticsearch.rest.BytesRestResponse;
import org.elasticsearch.rest.RestRequest;
import org.elasticsearch.rest.RestStatus;
import org.nlpcn.es4sql.SearchDao;
import org.nlpcn.es4sql.exception.SqlParseException;
import org.nlpcn.es4sql.query.QueryAction;

import java.io.IOException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.elasticsearch.rest.RestRequest.Method.GET;
import static org.elasticsearch.rest.RestRequest.Method.POST;


public class RestSqlAction extends BaseRestHandler {

    private static final Logger LOGGER = LogManager.getLogger();

    @Override
    public String getName() {
        return "sql_action";
    }

    @Override
    public List<Route> routes() {
        return Collections.unmodifiableList(Arrays.asList(
                new Route(POST, "/_nlpcn/sql/explain"),
                new Route(GET, "/_nlpcn/sql/explain"),
                new Route(POST, "/_nlpcn/sql"),
                new Route(GET, "/_nlpcn/sql")));
    }

    @Override
    protected RestChannelConsumer prepareRequest(RestRequest request, NodeClient client) {
        try (XContentParser parser = request.contentOrSourceParamParser()) {
            parser.mapStrings().forEach((k, v) -> request.params().putIfAbsent(k, v));
        } catch (IOException e) {
            // LOGGER.warn("Please use json format params, like: {\"sql\":\"SELECT * FROM test\"}");
        }

        String sql = request.param("sql");

        if (sql == null) {
            sql = request.content().utf8ToString();
        }
        try {
            SearchDao searchDao = new SearchDao(client);
            QueryAction queryAction = null;

            queryAction = searchDao.explain(sql);//zhongshu-comment ??????????????????sql????????????????????????Java????????????

            // TODO add unit tests to explain. (rest level?)
            if (request.path().endsWith("/explain")) {
                final String jsonExplanation = queryAction.explain().explain();
                return channel -> channel.sendResponse(new BytesRestResponse(RestStatus.OK, XContentType.JSON.mediaType(), jsonExplanation));
            } else {
                Map<String, String> params = request.params();

                //zhongshu-comment ?????????????????????rest????????????es?????????RestExecutor???????????????????????????ElasticDefaultRestExecutor
                RestExecutor restExecutor = ActionRequestRestExecuterFactory.createExecutor(params.get("format"));
                final QueryAction finalQueryAction = queryAction;
                //doing this hack because elasticsearch throws exception for un-consumed props
                Map<String, String> additionalParams = new HashMap<>();
                for (String paramName : responseParams()) {
                    if (request.hasParam(paramName)) {
                        additionalParams.put(paramName, request.param(paramName));
                    }
                }
                //zhongshu-comment restExecutor.execute()??????????????????es???????????????rest api
                //zhongshu-comment restExecutor.execute()????????????1???4??????????????????????????????????????????2???3??????????????????????????????????????????????????????????????????
                //zhongshu-comment ??????????????????ElasticDefaultRestExecutor????????????
                //todo ???????????????????????????java8 -> lambda????????????https://blog.csdn.net/ioriogami/article/details/12782141
                return channel -> restExecutor.execute(client, additionalParams, finalQueryAction, channel);
            }
        } catch (SqlParseException | SQLFeatureNotSupportedException e) {
            e.printStackTrace();
        }
        return null;
    }

    @Override
    protected Set<String> responseParams() {
        Set<String> responseParams = new HashSet<>(super.responseParams());
        responseParams.addAll(Arrays.asList("sql", "flat", "separator", "_score", "_type", "_id", "_scroll_id", "newLine", "format", "showHeader", "quote"));
        return Collections.unmodifiableSet(responseParams);
    }
}