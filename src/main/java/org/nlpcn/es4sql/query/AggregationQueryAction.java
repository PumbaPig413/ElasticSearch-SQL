package org.nlpcn.es4sql.query;

import com.google.common.collect.Lists;
import org.elasticsearch.action.search.SearchAction;
import org.elasticsearch.action.search.SearchRequestBuilder;
import org.elasticsearch.action.search.SearchType;
import org.elasticsearch.client.Client;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.join.aggregations.JoinAggregationBuilders;
import org.elasticsearch.search.aggregations.AggregationBuilder;
import org.elasticsearch.search.aggregations.AggregationBuilders;
import org.elasticsearch.search.aggregations.BucketOrder;
import org.elasticsearch.search.aggregations.PipelineAggregationBuilder;
import org.elasticsearch.search.aggregations.PipelineAggregatorBuilders;
import org.elasticsearch.search.aggregations.bucket.histogram.DateHistogramAggregationBuilder;
import org.elasticsearch.search.aggregations.bucket.nested.NestedAggregationBuilder;
import org.elasticsearch.search.aggregations.bucket.nested.ReverseNestedAggregationBuilder;
import org.elasticsearch.search.aggregations.bucket.terms.TermsAggregationBuilder;
import org.elasticsearch.search.sort.SortOrder;
import org.nlpcn.es4sql.Util;
import org.nlpcn.es4sql.domain.Field;
import org.nlpcn.es4sql.domain.KVValue;
import org.nlpcn.es4sql.domain.MethodField;
import org.nlpcn.es4sql.domain.Order;
import org.nlpcn.es4sql.domain.Select;
import org.nlpcn.es4sql.domain.Where;
import org.nlpcn.es4sql.domain.hints.Hint;
import org.nlpcn.es4sql.domain.hints.HintType;
import org.nlpcn.es4sql.exception.SqlParseException;
import org.nlpcn.es4sql.query.maker.AggMaker;
import org.nlpcn.es4sql.query.maker.QueryMaker;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Transform SQL query to Elasticsearch aggregations query
 */
public class AggregationQueryAction extends QueryAction {

    private final Select select;
    private AggMaker aggMaker = new AggMaker();
    private SearchRequestBuilder request;

    public AggregationQueryAction(Client client, Select select) {
        super(client, select);
        this.select = select;
    }

    @Override
    public SqlElasticSearchRequestBuilder explain() throws SqlParseException {
//        this.request = client.prepareSearch();//zhongshu-comment elastic6.1.1?????????
        this.request = new SearchRequestBuilder(client, SearchAction.INSTANCE); //zhongshu-comment master?????????

        //????????? AggregationBuilder???????????????????????????PipelineAggregation?????? "max_bucket", "min_bucket"
        List<Field> bucketFields = Lists.newArrayList();
        List<Field> _fields =  select.getFields().stream().filter(field -> {
            if (field.getName().startsWith("max_bucket") || field.getName().startsWith("min_bucket")) {
                bucketFields.add(field);
                return false;
            }
            return true;
        }).collect(Collectors.toList());

        select.setFields(_fields);
        if (bucketFields.size() > 0) {
            bucketFields.stream().forEach(field -> {
                String bucketPath = ((MethodField)field).getParams().get(0).toString().replace("=", ">");
                PipelineAggregationBuilder pipAgg = null;
                if (field.getName().equals("max_bucket")) {
                      pipAgg = PipelineAggregatorBuilders.maxBucket(field.getAlias(), bucketPath);
                } else  if (field.getName().equals("min_bucket")) {
                      pipAgg = PipelineAggregatorBuilders.minBucket(field.getAlias(), bucketPath);
                }
                if (null != pipAgg) {
                    request.addAggregation(pipAgg);
                }
            });
        }

        setIndicesAndTypes();

        setWhere(select.getWhere()); //zhongshu-comment ???DefaultQueryAction???setWhere()??????
        AggregationBuilder lastAgg = null;
        //zhongshu-comment ??????es???aggs????????????????????????a????????????group by ???,?????????b????????????group by ????????????????????????select???groupBys???????????????List???????????????a??????b??????????????????????????????group by????????????
        for (List<Field> groupBy : select.getGroupBys()) {
            if (!groupBy.isEmpty()) {
                Field field = groupBy.get(0);

                //zhongshu-comment ??????group by????????????select????????????????????????
                //make groupby can reference to field alias
                lastAgg = getGroupAgg(field, select);

                /*
                zhongshu-comment ??????limit??????200?????????shard size?????????5000???
                                 ??????limit??????200?????????shard size??????size????????????
                 */
                if (lastAgg != null && lastAgg instanceof TermsAggregationBuilder && !(field instanceof MethodField)) {
                    //if limit size is too small, increasing shard  size is required
                    if (select.getRowCount() < 200) {
                        ((TermsAggregationBuilder) lastAgg).shardSize(5000);
                        for (Hint hint : select.getHints()) {
                            if (hint.getType() == HintType.SHARD_SIZE) {
                                if (hint.getParams() != null && hint.getParams().length != 0 && hint.getParams()[0] != null) {
                                    ((TermsAggregationBuilder) lastAgg).shardSize((Integer) hint.getParams()[0]);
                                }
                            }
                        }
                    }

                    setSize(lastAgg, field);
                    setShardSize(lastAgg);
                }

                if (field.isNested()) {
                    AggregationBuilder nestedBuilder = createNestedAggregation(field);

                    if (insertFilterIfExistsAfter(lastAgg, groupBy, nestedBuilder, 1)) {
                        groupBy.remove(1);
                    } else {
                        nestedBuilder.subAggregation(lastAgg);
                    }

                    request.addAggregation(wrapNestedIfNeeded(nestedBuilder, field.isReverseNested()));
                } else if (field.isChildren()) {
                    AggregationBuilder childrenBuilder = createChildrenAggregation(field);

                    if (insertFilterIfExistsAfter(lastAgg, groupBy, childrenBuilder, 1)) {
                        groupBy.remove(1);
                    } else {
                        childrenBuilder.subAggregation(lastAgg);
                    }

                    request.addAggregation(childrenBuilder);
                } else {
                    request.addAggregation(lastAgg);
                }

                //zhongshu-comment ?????????1??????
                for (int i = 1; i < groupBy.size(); i++) {
                    field = groupBy.get(i);
                    AggregationBuilder subAgg = getGroupAgg(field, select);
                      //ES5.0 termsaggregation with size = 0 not supported anymore
//                    if (subAgg instanceof TermsAggregationBuilder && !(field instanceof MethodField)) {

//                        //((TermsAggregationBuilder) subAgg).size(0);
//                    }
                    setSize(subAgg, field);
                    setShardSize(subAgg);
                    if (field.isNested()) {
                        AggregationBuilder nestedBuilder = createNestedAggregation(field);

                        if (insertFilterIfExistsAfter(subAgg, groupBy, nestedBuilder, i + 1)) {
                            groupBy.remove(i + 1);
                            i++;
                        } else {
                            nestedBuilder.subAggregation(subAgg);
                        }

                        lastAgg.subAggregation(wrapNestedIfNeeded(nestedBuilder, field.isReverseNested()));
                    } else if (field.isChildren()) {
                        AggregationBuilder childrenBuilder = createChildrenAggregation(field);

                        if (insertFilterIfExistsAfter(subAgg, groupBy, childrenBuilder, i + 1)) {
                            groupBy.remove(i + 1);
                            i++;
                        } else {
                            childrenBuilder.subAggregation(subAgg);
                        }

                        lastAgg.subAggregation(childrenBuilder);
                    } else {
                        lastAgg.subAggregation(subAgg);
                    }

                    //zhongshu-comment ???lastAgg??????subAgg?????????????????????????????????????????????????????????????????????a aggs?????????b aggs???b aggs?????????c aggs???c aggs?????????d aggs
                    lastAgg = subAgg;
                }//zhongshu-comment ????????????aggs????????????
            }

            // add aggregation function to each groupBy zhongshu-comment each groupBy???????????????aggs
            /*
            zhongshu-comment ???????????????????????????group by??????????????????????????????group by???????????????????????????????????????????????????????????????????????????count???sum?????????avg??????
                             ?????????????????????????????????select?????????????????????
            ?????????select c,d,sum(a),count(b) from tbl group by c,d;
            ???????????????????????????group by????????????c???d??????????????? explanFields() ????????????sum(a)???count(b)???
             */
            explanFields(request, select.getFields(), lastAgg);

        }//zhongshu-comment ????????????aggs????????????

        if (select.getGroupBys().size() < 1) {
            //add aggregation when having no groupBy script
            /*
            zhongshu-comment ??????sql?????????group by?????????????????????????????????????????????aggs????????????sql???
            select sum(a),count(b) from tbl;
            ????????????????????????????????????????????????????????????????????????????????????????????????????????????aggs???
             */
            explanFields(request, select.getFields(), lastAgg);

        }

        Map<String, KVValue> groupMap = aggMaker.getGroupMap();
        // add field
        if (select.getFields().size() > 0) {
            setFields(select.getFields());
//            explanFields(request, select.getFields(), lastAgg);
        }

        // add order
        if (lastAgg != null && select.getOrderBys().size() > 0) {
            for (Order order : select.getOrderBys()) {
                KVValue temp = groupMap.get(order.getName());
                if (temp != null) {
                    //TermsAggregationBuilder termsBuilder = (TermsAggregationBuilder) temp.value;
                    //modified by xzb ?????? DateHistogramAggregationBuilder ??????????????????????????????????????????????????????
                    if (temp.value instanceof TermsAggregationBuilder) {
                        TermsAggregationBuilder  aggsBuilder = (TermsAggregationBuilder) temp.value;
                        switch (temp.key) {
                            case "COUNT":
                                String orderName = order.getName();
                                if (isAliasFiled(orderName)) {
                                    aggsBuilder.order(BucketOrder.aggregation(orderName, isASC(order)));
                                } else {
                                    aggsBuilder.order(BucketOrder.count(isASC(order)));
                                }
                                break;
                            case "KEY":
                                aggsBuilder.order(BucketOrder.key(isASC(order)));
                                // add the sort to the request also so the results get sorted as well
                                request.addSort(order.getName(), SortOrder.valueOf(order.getType()));
                                break;
                            case "FIELD":
                                aggsBuilder.order(BucketOrder.aggregation(order.getName(), isASC(order)));
                                break;
                            default:
                                throw new SqlParseException(order.getName() + " can not to order");
                        }
                    } else if (temp.value instanceof DateHistogramAggregationBuilder) {
                        DateHistogramAggregationBuilder aggsBuilder = (DateHistogramAggregationBuilder) temp.value;
                        switch (temp.key) {
                            case "COUNT":
                                String orderName = order.getName();
                                if (isAliasFiled(orderName)) {
                                    aggsBuilder.order(BucketOrder.aggregation(orderName, isASC(order)));
                                } else {
                                    aggsBuilder.order(BucketOrder.count(isASC(order)));
                                }
                                break;
                            case "KEY":
                                aggsBuilder.order(BucketOrder.key(isASC(order)));
                                // add the sort to the request also so the results get sorted as well
                                request.addSort(order.getName(), SortOrder.valueOf(order.getType()));
                                break;
                            case "FIELD":
                                aggsBuilder.order(BucketOrder.aggregation(order.getName(), isASC(order)));
                                break;
                            default:
                                throw new SqlParseException(order.getName() + " can not to order");
                        }
                    }
                } else {
                    request.addSort(order.getName(), SortOrder.valueOf(order.getType()));
                }
            }
        }
        //zhongshu-comment ??????????????????
        setLimitFromHint(this.select.getHints());

        request.setSearchType(SearchType.DEFAULT);
        updateRequestWithIndexAndRoutingOptions(select, request);
        updateRequestWithHighlight(select, request);
        updateRequestWithCollapse(select, request);
        updateRequestWithPostFilter(select, request);
        updateRequestWithStats(select, request);
        updateRequestWithPreference(select, request);
        updateRequestWithTrackTotalHits(select, request);
        updateRequestWithTimeout(select, request);
        updateRequestWithIndicesOptions(select, request);
        updateRequestWithMinScore(select, request);
        updateRequestWithSearchAfter(select, request);
        updateRequestWithRuntimeMappings(select, request);
        SqlElasticSearchRequestBuilder sqlElasticRequestBuilder = new SqlElasticSearchRequestBuilder(request);
        return sqlElasticRequestBuilder;
    }

    private void setSize (AggregationBuilder agg, Field field) {
        if (field instanceof MethodField) { //zhongshu-comment MethodField????????????????????????size
            MethodField mf = ((MethodField) field);
            Object customSize = mf.getParamsAsMap().get("size");
            if (customSize == null) { //zhongshu-comment ?????????????????????MethodField??????agg???size??????????????????rowCount?????????agg???size
                if(select.getRowCount()>0) {
                    if (agg instanceof TermsAggregationBuilder) {
                        ((TermsAggregationBuilder) agg).size(select.getRowCount());
                    }
                }
            } else {
                //zhongshu-comment ????????????????????????????????????????????????????????????????????????size?????????agg????????????
            }
        } else {
            if(select.getRowCount()>0) {
                if (agg instanceof TermsAggregationBuilder) {
                    ((TermsAggregationBuilder) agg).size(select.getRowCount());
                }
            }
        }
    }

    private void setShardSize(AggregationBuilder agg) {
        if (agg instanceof TermsAggregationBuilder) {
            int defaultShardSize = 20 * select.getRowCount();
            ((TermsAggregationBuilder) agg).shardSize(Math.max(defaultShardSize, 5000));
        }
    }

    private AggregationBuilder getGroupAgg(Field field, Select select2) throws SqlParseException {
        boolean refrence = false;
        AggregationBuilder lastAgg = null;
        for (Field temp : select.getFields()) {
            if (temp instanceof MethodField && temp.getName().equals("script")) {
                MethodField scriptField = (MethodField) temp;
                for (KVValue kv : scriptField.getParams()) {
                    if (kv.value.equals(field.getName())) {
                        lastAgg = aggMaker.makeGroupAgg(scriptField, select);
                        refrence = true;
                        break;
                    }
                }
            }
        }

        /*
        zhongshu-comment reference??????????????????????????????????????????????????????group by????????????select????????????????????????
        refrence???false???????????????????????????????????????????????????Field????????????group by?????????????????????????????????
        "aggs":{
            "city_agg":{
                "field":"city"
             }
         }
         */
        if (!refrence)
            lastAgg = aggMaker.makeGroupAgg(field, select);
        
        return lastAgg;
    }

    private AggregationBuilder wrapNestedIfNeeded(AggregationBuilder nestedBuilder, boolean reverseNested) {
        if (!reverseNested) return nestedBuilder;
        if (reverseNested && !(nestedBuilder instanceof NestedAggregationBuilder)) return nestedBuilder;
        //we need to jump back to root
        return AggregationBuilders.reverseNested(nestedBuilder.getName() + "_REVERSED").subAggregation(nestedBuilder);
    }

    private AggregationBuilder createNestedAggregation(Field field) {
        AggregationBuilder nestedBuilder;

        String nestedPath = field.getNestedPath();

        if (field.isReverseNested()) {
            if (nestedPath == null || !nestedPath.startsWith("~")) {
                ReverseNestedAggregationBuilder reverseNestedAggregationBuilder = AggregationBuilders.reverseNested(getNestedAggName(field));
                if(nestedPath!=null){
                    reverseNestedAggregationBuilder.path(nestedPath);
                }
                return reverseNestedAggregationBuilder;
            }
            nestedPath = nestedPath.substring(1);
        }

        nestedBuilder = AggregationBuilders.nested(getNestedAggName(field),nestedPath);

        return nestedBuilder;
    }

    private AggregationBuilder createChildrenAggregation(Field field) {
        AggregationBuilder childrenBuilder;

        String childType = field.getChildType();

        childrenBuilder = Util.parseAggregationBuilder(JoinAggregationBuilders.children(getChildrenAggName(field), childType));

        return childrenBuilder;
    }

    private String getNestedAggName(Field field) {
        String prefix;

        if (field instanceof MethodField) {
            String nestedPath = field.getNestedPath();
            if (nestedPath != null) {
                prefix = nestedPath;
            } else {
                prefix = field.getAlias();
            }
        } else {
            prefix = field.getName();
        }
        return prefix + "@NESTED";
    }

    private String getChildrenAggName(Field field) {
        String prefix;

        if (field instanceof MethodField) {
            String childType = field.getChildType();

            if (childType != null) {
                prefix = childType;
            } else {
                prefix = field.getAlias();
            }
        } else {
            prefix = field.getName();
        }

        return prefix + "@CHILDREN";
    }

    private boolean insertFilterIfExistsAfter(AggregationBuilder agg, List<Field> groupBy, AggregationBuilder builder, int nextPosition) throws SqlParseException {
        if (groupBy.size() <= nextPosition) return false;
        Field filterFieldCandidate = groupBy.get(nextPosition);
        if (!(filterFieldCandidate instanceof MethodField)) return false;
        MethodField methodField = (MethodField) filterFieldCandidate;
        if (!methodField.getName().toLowerCase().equals("filter")) return false;
        builder.subAggregation(aggMaker.makeGroupAgg(filterFieldCandidate, select).subAggregation(agg));
        return true;
    }

    private AggregationBuilder updateAggIfNested(AggregationBuilder lastAgg, Field field) {
        if (field.isNested()) {
            lastAgg = AggregationBuilders.nested(field.getName() + "Nested",field.getNestedPath())
                    .subAggregation(lastAgg);
        }
        return lastAgg;
    }

    private boolean isASC(Order order) {
        return "ASC".equals(order.getType());
    }

    private void setFields(List<Field> fields) {
        if (select.getFields().size() > 0) {
            ArrayList<String> includeFields = new ArrayList<>();

            for (Field field : fields) {
                if (field != null) {
                    includeFields.add(field.getName());
                }
            }

            request.setFetchSource(includeFields.toArray(new String[includeFields.size()]), null);
        }
    }

    private void explanFields(SearchRequestBuilder request, List<Field> fields, AggregationBuilder groupByAgg) throws SqlParseException {
        for (Field field : fields) {

            if (field instanceof MethodField) {

                if (field.getName().equals("script")) {
                    //question addStoredField()???????????????
                    request.addStoredField(field.getAlias());

                    /*
                    zhongshu-comment ???request?????????defaultQueryAction?????????????????????setFields()????????????????????????request.setFetchSource(),
                                     ???request??????include?????????exclude??????
                     */
                    DefaultQueryAction defaultQueryAction = new DefaultQueryAction(client, select);
                    defaultQueryAction.intialize(request);
                    List<Field> tempFields = Lists.newArrayList(field);
                    defaultQueryAction.setFields(tempFields);

                    /*
                     zhongshu-comment ??????field.getName().equals("script")?????????????????????????????????????????????????????????????????????metric???
                                        ????????????continue??????????????????????????????agg
                    */
                    continue;
                }

                //modify by xzb ??????????????????????????????????????? makeMovingFieldAgg??????
                if (groupByAgg != null) {
                    if (field.getName().startsWith("rollingstd") || field.getName().startsWith("movingavg")) {
                        groupByAgg.subAggregation(aggMaker.makeMovingFieldAgg((MethodField) field, groupByAgg));
                    } else {
                        groupByAgg.subAggregation(aggMaker.makeFieldAgg((MethodField) field, groupByAgg));
                    }
                } else {
                    //question ????????????????????????null???agg??????request??????????????????dsl?????????????????????????????????
                    request.addAggregation(aggMaker.makeFieldAgg((MethodField) field, groupByAgg));
                }
            } else if (field instanceof Field) {

                //question ?????????Filed???????????????????????????MethodField????????????????????????include???exclude?????????request.setFetchSource()
                request.addStoredField(field.getName());
            } else {
                throw new SqlParseException("it did not support this field method " + field);
            }
        }
    }

    /**
     * Create filters based on
     * the Where clause.
     *
     * @param where the 'WHERE' part of the SQL query.
     * @throws SqlParseException
     */
    private void setWhere(Where where) throws SqlParseException {
        if (where != null) {
            QueryBuilder whereQuery = QueryMaker.explan(where,this.select.isQuery);
            request.setQuery(whereQuery);
        }
    }


    /**
     * Set indices and types to the search request.
     */
    private void setIndicesAndTypes() {
        request.setIndices(query.getIndexArr());

        String[] typeArr = query.getTypeArr();
        if (typeArr != null) {
            request.setTypes(typeArr);
        }
    }

    private void setLimitFromHint(List<Hint> hints) {
        int from = 0;
        int size = 0;
        for (Hint hint : hints) {
            if (hint.getType() == HintType.DOCS_WITH_AGGREGATION) {
                Integer[] params = (Integer[]) hint.getParams();
                if (params.length > 1) {
                    // if 2 or more are given, use the first as the from and the second as the size
                    // so it is the same as LIMIT from,size
                    // except written as /*! DOCS_WITH_AGGREGATION(from,size) */
                    from = params[0];
                    size = params[1];
                } else if (params.length == 1) {
                    // if only 1 parameter is given, use it as the size with a from of 0
                    size = params[0];
                }
                break;
            }
        }
        request.setFrom(from);
        request.setSize(size);
    }

    /**
     * ???????????????????????????????????????
     */
    private boolean isAliasFiled(String filedName) {
        if (select.getFields().size() > 0) {
            for (Field field : select.getFields()) {
                if (null != field.getAlias() && field.getAlias().equals(filedName)) {
                    return true;
                }
            }
        }
        return false;
    }

}
