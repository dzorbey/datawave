package datawave.query.iterator;

import com.google.common.base.CharMatcher;
import com.google.common.base.Function;
import com.google.common.base.Predicate;
import com.google.common.base.Splitter;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Iterators;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.google.common.collect.Queues;
import com.google.common.collect.Sets;
import java.net.MalformedURLException;
import datawave.core.iterators.ColumnRangeIterator;
import datawave.core.iterators.DatawaveFieldIndexCachingIteratorJexl.HdfsBackedControl;
import datawave.core.iterators.filesystem.FileSystemCache;
import datawave.core.iterators.querylock.QueryLock;
import datawave.data.type.Type;
import datawave.query.Constants;
import datawave.query.DocumentSerialization;
import datawave.query.attributes.Document;
import datawave.query.function.ConfiguredFunction;
import datawave.query.function.Equality;
import datawave.query.function.GetStartKey;
import datawave.query.function.PrefixEquality;
import datawave.query.iterator.filter.EventKeyDataTypeFilter;
import datawave.query.iterator.filter.FieldIndexKeyDataTypeFilter;
import datawave.query.iterator.filter.KeyIdentity;
import datawave.query.iterator.filter.StringToText;
import datawave.query.iterator.logic.IndexIterator;
import datawave.query.jexl.DefaultArithmetic;
import datawave.query.jexl.HitListArithmetic;
import datawave.query.jexl.functions.FieldIndexAggregator;
import datawave.query.jexl.functions.IdentityAggregator;
import datawave.query.planner.SeekingQueryPlanner;
import datawave.query.predicate.ConfiguredPredicate;
import datawave.query.predicate.EventDataQueryFilter;
import datawave.query.predicate.TimeFilter;
import datawave.query.statsd.QueryStatsDClient;
import datawave.query.tables.async.Scan;
import datawave.query.util.CompositeMetadata;
import datawave.query.util.TypeMetadata;
import datawave.query.util.TypeMetadataProvider;
import datawave.util.StringUtils;
import org.apache.accumulo.core.data.Key;
import org.apache.accumulo.core.data.PartialKey;
import org.apache.accumulo.core.data.Range;
import org.apache.accumulo.core.iterators.OptionDescriber;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.jexl2.JexlArithmetic;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.io.Text;
import org.apache.log4j.Logger;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Queue;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.apache.zookeeper.server.quorum.QuorumPeerConfig.ConfigException;

public class QueryOptions implements OptionDescriber {
    private static final Logger log = Logger.getLogger(QueryOptions.class);
    
    protected static Cache<String,FileSystem> fileSystemCache = CacheBuilder.newBuilder().concurrencyLevel(10).maximumSize(100).build();
    
    public static final Charset UTF8 = Charset.forName("utf-8");
    
    public static final String DEBUG_MULTITHREADED_SOURCES = "debug.multithreaded.sources";
    
    public static final String SCAN_ID = Scan.SCAN_ID;
    public static final String DISABLE_EVALUATION = "disable.evaluation";
    public static final String DISABLE_FIELD_INDEX_EVAL = "disable.fi";
    public static final String LIMIT_OVERRIDE = "disable.fi.override";
    public static final String LIMIT_SOURCES = "sources.limit.count";
    public static final String DISABLE_DOCUMENTS_WITHOUT_EVENTS = "disable.index.only.documents";
    public static final String QUERY = "query";
    public static final String QUERY_ID = "query.id";
    public static final String TYPE_METADATA = "type.metadata";
    public static final String TYPE_METADATA_AUTHS = "type.metadata.auths";
    public static final String METADATA_TABLE_NAME = "model.table.name";
    
    public static final String REDUCED_RESPONSE = "reduced.response";
    public static final String FULL_TABLE_SCAN_ONLY = "full.table.scan.only";
    
    public static final String PROJECTION_FIELDS = "projection.fields";
    public static final String BLACKLISTED_FIELDS = "blacklisted.fields";
    public static final String INDEX_ONLY_FIELDS = "index.only.fields";
    public static final String COMPOSITE_FIELDS = "composite.fields";
    public static final String COMPOSITE_METADATA = "composite.metadata";
    public static final String CONTAINS_COMPOSITE_TERMS = "composite.terms";
    public static final String IGNORE_COLUMN_FAMILIES = "ignore.column.families";
    public static final String INCLUDE_GROUPING_CONTEXT = "include.grouping.context";
    public static final String TERM_FREQUENCY_FIELDS = "term.frequency.fields";
    public static final String TERM_FREQUENCIES_REQUIRED = "term.frequencies.are.required";
    public static final String CONTENT_EXPANSION_FIELDS = "content.expansion.fields";
    public static final String LIMIT_FIELDS = "limit.fields";
    public static final String LIMIT_FIELDS_PRE_QUERY_EVALUATION = "limit.fields.pre.query.evaluation";
    public static final String LIMIT_FIELDS_FIELD = "limit.fields.field";
    public static final String GROUP_FIELDS = "group.fields";
    public static final String TYPE_METADATA_IN_HDFS = "type.metadata.in.hdfs";
    public static final String HITS_ONLY = "hits.only";
    public static final String HIT_LIST = "hit.list";
    public static final String START_TIME = "start.time";
    public static final String END_TIME = "end.time";
    public static final String YIELD_THRESHOLD_MS = "yield.threshold.ms";
    
    public static final String FILTER_MASKED_VALUES = "filter.masked.values";
    public static final String INCLUDE_DATATYPE = "include.datatype";
    public static final String INCLUDE_RECORD_ID = "include.record.id";
    public static final String LOG_TIMING_DETAILS = "log.timing.details";
    public static final String COLLECT_TIMING_DETAILS = "collect.timing.details";
    public static final String STATSD_HOST_COLON_PORT = "statsd.host.colon.port";
    public static final String STATSD_MAX_QUEUE_SIZE = "statsd.max.queue.size";
    public static final String DATATYPE_FIELDNAME = "include.datatype.fieldname";
    public static final String TRACK_SIZES = "track.sizes";
    
    // pass through to Evaluating iterator to ensure consistency between query
    // logics
    
    // TODO: This DEFAULT_DATATYPE_FIELDNAME needs to be decided on
    public static final String DEFAULT_DATATYPE_FIELDNAME = "EVENT_DATATYPE";
    
    public static final String DEFAULT_PARENT_UID_FIELDNAME = Constants.PARENT_UID;
    
    public static final String DEFAULT_CHILD_COUNT_FIELDNAME = Constants.CHILD_COUNT;
    public static final String DEFAULT_DESCENDANT_COUNT_FIELDNAME = "DESCENDANT_COUNT";
    public static final String DEFAULT_HAS_CHILDREN_FIELDNAME = "HAS_CHILDREN";
    public static final String CHILD_COUNT_OUTPUT_IMMEDIATE_CHILDREN = "childcount.output.immediate";
    public static final String CHILD_COUNT_OUTPUT_ALL_DESCDENDANTS = "childcount.output.descendants";
    public static final String CHILD_COUNT_OUTPUT_HASCHILDREN = "childcount.output.haschildren";
    public static final String CHILD_COUNT_INDEX_DELIMITER = "childcount.index.delimiter";
    public static final String CHILD_COUNT_INDEX_FIELDNAME = "childcount.index.fieldname";
    public static final String CHILD_COUNT_INDEX_PATTERN = "childcount.index.pattern";
    public static final String CHILD_COUNT_INDEX_SKIP_THRESHOLD = "childcount.index.skip.threshold";
    
    public static final String INCLUDE_HIERARCHY_FIELDS = "include.hierarchy.fields";
    
    public static final String DATATYPE_FILTER = "datatype.filter";
    
    public static final String POSTPROCESSING_CLASSES = "postprocessing.classes";
    
    public static final String POSTPROCESSING_OPTIONS = "postprocessing.options";
    
    public static final String NON_INDEXED_DATATYPES = "non.indexed.dataTypes";
    
    public static final String EVERYTHING = "*";
    
    public static final String CONTAINS_INDEX_ONLY_TERMS = "contains.index.only.terms";
    
    public static final String ALLOW_FIELD_INDEX_EVALUATION = "allow.field.index.evaluation";
    
    public static final String ALLOW_TERM_FREQUENCY_LOOKUP = "allow.term.frequency.lookup";
    
    public static final String HDFS_SITE_CONFIG_URLS = "hdfs.site.config.urls";
    
    public static final String HDFS_FILE_COMPRESSION_CODEC = "hdfs.file.compression.codec";
    
    public static final String ZOOKEEPER_CONFIG = "zookeeper.config";
    
    public static final String IVARATOR_CACHE_BASE_URI_ALTERNATIVES = "ivarator.cache.base.uri.alternatives";
    
    public static final String IVARATOR_CACHE_BUFFER_SIZE = "ivarator.cache.buffer.size";
    
    public static final String IVARATOR_SCAN_PERSIST_THRESHOLD = "ivarator.scan.persist.threshold";
    
    public static final String IVARATOR_SCAN_TIMEOUT = "ivarator.scan.timeout";
    
    public static final String QUERY_MAPPING_COMPRESS = "query.mapping.compress";
    
    public static final String MAX_INDEX_RANGE_SPLIT = "max.index.range.split";
    
    public static final String MAX_IVARATOR_OPEN_FILES = "max.ivarator.open.files";
    
    public static final String MAX_IVARATOR_SOURCES = "max.ivarator.sources";
    
    public static final String COMPRESS_SERVER_SIDE_RESULTS = "compress.server.side.results";
    
    public static final String MAX_EVALUATION_PIPELINES = "max.evaluation.pipelines";
    
    public static final String SERIAL_EVALUATION_PIPELINE = "serial.evaluation.pipeline";
    
    public static final String MAX_PIPELINE_CACHED_RESULTS = "max.pipeline.cached.results";
    
    public static final String BATCHED_QUERY = "query.iterator.batch";
    
    public static final String BATCHED_QUERY_RANGE_PREFIX = "query.iterator.batch.range.";
    
    public static final String BATCHED_QUERY_PREFIX = "query.iterator.batch.query.";
    
    public static final String DATE_INDEX_TIME_TRAVEL = "date.index.time.travel";
    
    public static final String SORTED_UIDS = "sorted.uids";
    
    public static final String DATA_QUERY_EXPRESSION_FILTER_ENABLED = "query.data.expression.filter.enabled";
    
    protected Map<String,String> options;
    
    protected String scanId;
    protected String query;
    protected String queryId;
    protected boolean disableEvaluation = false;
    protected boolean disableFiEval = false;
    protected long sourceLimit = -1;
    protected boolean disableIndexOnlyDocuments = false;
    protected TypeMetadata typeMetadata = new TypeMetadata();
    protected Set<String> typeMetadataAuthsKey = Sets.newHashSet();
    protected CompositeMetadata compositeMetadata = new CompositeMetadata();
    protected DocumentSerialization.ReturnType returnType = DocumentSerialization.ReturnType.kryo;
    protected boolean reducedResponse = false;
    protected boolean fullTableScanOnly = false;
    protected JexlArithmetic arithmetic = new DefaultArithmetic();
    
    protected boolean projectResults = false;
    protected boolean useWhiteListedFields = false;
    protected Set<String> whiteListedFields = new HashSet<>();
    protected boolean useBlackListedFields = false;
    protected Set<String> blackListedFields = new HashSet<>();
    protected Map<String,Integer> limitFieldsMap = new HashMap<>();
    protected boolean limitFieldsPreQueryEvaluation = false;
    protected String limitFieldsField = null;
    
    protected Set<String> groupFieldsSet = Sets.newHashSet();
    
    protected Set<String> hitsOnlySet = new HashSet<>();
    
    protected Function<Range,Key> getDocumentKey;
    
    protected FieldIndexAggregator fiAggregator;
    protected Equality equality;
    
    protected EventDataQueryFilter evaluationFilter;
    
    protected int maxEvaluationPipelines = 25;
    protected int maxPipelineCachedResults = 25;
    
    protected Set<String> indexOnlyFields = Sets.newHashSet();
    protected Set<String> ignoreColumnFamilies = Sets.newHashSet();
    
    protected boolean includeGroupingContext = false;
    
    protected long startTime = 0l;
    protected long endTime = System.currentTimeMillis();
    protected TimeFilter timeFilter = null;
    
    // this flag control whether we filter the masked fields for results that
    // contain both the unmasked and masked variants. True by default.
    
    protected boolean filterMaskedValues = true;
    
    protected boolean includeRecordId = true;
    protected boolean includeDatatype = false;
    protected boolean includeHierarchyFields = false;
    protected String datatypeKey;
    protected boolean containsIndexOnlyTerms = false;
    protected boolean mustUseFieldIndex = false;
    
    protected boolean allowFieldIndexEvaluation = true;
    
    protected boolean allowTermFrequencyLookup = true;
    
    protected String hdfsSiteConfigURLs = null;
    protected String hdfsFileCompressionCodec = null;
    protected FileSystemCache fsCache = null;
    
    protected String zookeeperConfig = null;
    
    protected List<String> ivaratorCacheBaseURIAlternatives = null;
    protected long ivaratorCacheScanPersistThreshold = 100000L;
    protected long ivaratorCacheScanTimeout = 1000L * 60 * 60;
    protected int ivaratorCacheBufferSize = 10000;
    
    protected int maxIndexRangeSplit = 11;
    protected int ivaratorMaxOpenFiles = 100;
    
    protected int maxIvaratorSources = 33;
    
    protected long yieldThresholdMs = Long.MAX_VALUE;
    
    protected Predicate<Key> fieldIndexKeyDataTypeFilter = KeyIdentity.Function;
    protected Predicate<Key> eventEntryKeyDataTypeFilter = KeyIdentity.Function;
    
    protected String postProcessingFunctions = "";
    
    protected Map<String,Set<String>> nonIndexedDataTypeMap = Maps.newHashMap();
    
    protected boolean termFrequenciesRequired = false;
    protected Set<String> termFrequencyFields = Collections.emptySet();
    protected Set<String> contentExpansionFields;
    
    protected boolean compressResults = false;
    
    protected Boolean compressedMappings = false;
    protected boolean limitOverride = false;
    
    // determine whether sortedUIDs are required. Normally they are, however if the query contains
    // only one indexed term, then there is no need to sort which can be a lot faster if an ivarator
    // is required.
    boolean sortedUIDs = true;
    
    protected boolean collectTimingDetails = false;
    
    protected String statsdHostAndPort = null;
    protected int statsdMaxQueueSize = 500;
    
    protected QueryStatsDClient statsdClient = null;
    
    protected boolean serialEvaluationPipeline = false;
    
    protected Queue<Entry<Range,String>> batchStack;
    
    protected TypeMetadataProvider typeMetadataProvider;
    
    protected int batchedQueries = 0;
    
    protected String metadataTableName;
    
    protected boolean dateIndexTimeTravel = false;
    
    protected boolean debugMultithreadedSources = false;
    
    protected boolean dataQueryExpressionFilterEnabled = false;
    
    /**
     * should document sizes be tracked
     */
    protected boolean trackSizes = true;
    
    public void deepCopy(QueryOptions other) {
        this.options = other.options;
        this.query = other.query;
        this.queryId = other.queryId;
        this.scanId = other.scanId;
        this.disableEvaluation = other.disableEvaluation;
        this.disableIndexOnlyDocuments = other.disableIndexOnlyDocuments;
        this.typeMetadata = other.typeMetadata;
        this.typeMetadataProvider = other.typeMetadataProvider;
        this.typeMetadataAuthsKey = other.typeMetadataAuthsKey;
        this.metadataTableName = other.metadataTableName;
        this.compositeMetadata = other.compositeMetadata;
        this.returnType = other.returnType;
        this.reducedResponse = other.reducedResponse;
        this.fullTableScanOnly = other.fullTableScanOnly;
        
        this.projectResults = other.projectResults;
        this.useWhiteListedFields = other.useWhiteListedFields;
        this.whiteListedFields = other.whiteListedFields;
        this.useBlackListedFields = other.useBlackListedFields;
        this.blackListedFields = other.blackListedFields;
        
        this.fiAggregator = other.fiAggregator;
        
        this.indexOnlyFields = other.indexOnlyFields;
        this.ignoreColumnFamilies = other.ignoreColumnFamilies;
        
        this.includeGroupingContext = other.includeGroupingContext;
        
        this.startTime = other.startTime;
        this.endTime = other.endTime;
        this.timeFilter = other.timeFilter;
        
        this.filterMaskedValues = other.filterMaskedValues;
        this.includeDatatype = other.includeDatatype;
        this.datatypeKey = other.datatypeKey;
        this.includeRecordId = other.includeRecordId;
        
        this.includeHierarchyFields = other.includeHierarchyFields;
        
        this.fieldIndexKeyDataTypeFilter = other.fieldIndexKeyDataTypeFilter;
        this.eventEntryKeyDataTypeFilter = other.eventEntryKeyDataTypeFilter;
        
        this.postProcessingFunctions = other.postProcessingFunctions;
        
        this.nonIndexedDataTypeMap = other.nonIndexedDataTypeMap;
        
        this.containsIndexOnlyTerms = other.containsIndexOnlyTerms;
        
        this.getDocumentKey = other.getDocumentKey;
        this.equality = other.equality;
        this.evaluationFilter = other.evaluationFilter;
        this.fiAggregator = other.fiAggregator;
        
        this.ivaratorCacheBaseURIAlternatives = other.ivaratorCacheBaseURIAlternatives;
        this.hdfsSiteConfigURLs = other.hdfsSiteConfigURLs;
        this.ivaratorCacheBufferSize = other.ivaratorCacheBufferSize;
        this.ivaratorCacheScanPersistThreshold = other.ivaratorCacheScanPersistThreshold;
        this.ivaratorCacheScanTimeout = other.ivaratorCacheScanTimeout;
        this.hdfsFileCompressionCodec = other.hdfsFileCompressionCodec;
        this.maxIndexRangeSplit = other.maxIndexRangeSplit;
        this.ivaratorMaxOpenFiles = other.ivaratorMaxOpenFiles;
        this.maxIvaratorSources = other.maxIvaratorSources;
        
        this.yieldThresholdMs = other.yieldThresholdMs;
        
        this.compressResults = other.compressResults;
        this.limitFieldsMap = other.limitFieldsMap;
        this.limitFieldsPreQueryEvaluation = other.limitFieldsPreQueryEvaluation;
        this.limitFieldsField = other.limitFieldsField;
        this.groupFieldsSet = other.groupFieldsSet;
        this.hitsOnlySet = other.hitsOnlySet;
        
        this.compressedMappings = other.compressedMappings;
        this.limitOverride = other.limitOverride;
        
        this.sortedUIDs = other.sortedUIDs;
        
        this.compressedMappings = other.compressedMappings;
        this.limitOverride = other.limitOverride;
        
        this.sortedUIDs = other.sortedUIDs;
        
        this.compressedMappings = other.compressedMappings;
        this.limitOverride = other.limitOverride;
        
        this.sortedUIDs = other.sortedUIDs;
        
        this.compressedMappings = other.compressedMappings;
        this.limitOverride = other.limitOverride;
        
        this.sortedUIDs = other.sortedUIDs;
        
        this.termFrequenciesRequired = other.termFrequenciesRequired;
        this.termFrequencyFields = other.termFrequencyFields;
        this.contentExpansionFields = other.contentExpansionFields;
        
        this.batchedQueries = other.batchedQueries;
        this.batchStack = other.batchStack;
        this.maxEvaluationPipelines = other.maxEvaluationPipelines;
        
        this.dateIndexTimeTravel = other.dateIndexTimeTravel;
        
        this.debugMultithreadedSources = other.debugMultithreadedSources;
        
        this.dataQueryExpressionFilterEnabled = other.dataQueryExpressionFilterEnabled;
        
        this.trackSizes = other.trackSizes;
    }
    
    public String getQuery() {
        return query;
    }
    
    public void setQuery(String query) {
        this.query = query;
    }
    
    public String getQueryId() {
        return queryId;
    }
    
    public void setQueryId(String queryId) {
        this.queryId = queryId;
    }
    
    public String getScanId() {
        return scanId;
    }
    
    public void setScanId(String scanId) {
        this.scanId = scanId;
    }
    
    public boolean isDisableEvaluation() {
        return disableEvaluation;
    }
    
    public void setDisableEvaluation(boolean disableEvaluation) {
        this.disableEvaluation = disableEvaluation;
    }
    
    public boolean disableIndexOnlyDocuments() {
        return disableIndexOnlyDocuments;
    }
    
    public void setDisableIndexOnlyDocuments(boolean disableIndexOnlyDocuments) {
        this.disableIndexOnlyDocuments = disableIndexOnlyDocuments;
    }
    
    public TypeMetadata getTypeMetadata() {
        
        // first, we will see it the query passed over the serialized TypeMetadata.
        // If it did, use that.
        if (this.typeMetadata != null && this.typeMetadata.size() != 0) {
            
            return this.typeMetadata;
            
            // if the query did not contain the TypeMetadata in its options,
            // (the TypeMetadata class member is empty) we will attempt to
            // use the hdfs typeMetadata from the TypeMetadataProvider. The query will have sent
            // us the auths to use as a key:
        } else if (this.metadataTableName != null && this.typeMetadataAuthsKey != null) {
            log.debug("the query did not pass the typeMetadata");
            // lazily create a typeMetadataProvider if we don't already have one
            if (this.typeMetadataProvider == null) {
                try {
                    this.typeMetadataProvider = TypeMetadataProvider.Factory.createTypeMetadataProvider();
                    if (log.isTraceEnabled()) {
                        log.trace("made a typeMetadataProvider:" + typeMetadataProvider);
                    }
                } catch (Throwable th) {
                    // for now, do not allow problems with the TypeMetadataProvider to affect instantiation.
                    log.info("was unable to create a TypeMetadataProvider from its Factory: ", th);
                }
            }
            if (this.typeMetadataProvider != null) {
                TypeMetadata typeMetadata = this.typeMetadataProvider.getTypeMetadata(this.metadataTableName, this.typeMetadataAuthsKey);
                if (typeMetadata != null) {
                    if (log.isDebugEnabled()) {
                        log.debug("got a typeMetadata from hdfs and the bridge uri is " + typeMetadataProvider.getBridge().getUri());
                    }
                    return typeMetadata;
                }
            }
        }
        log.debug("making a nothing typeMetadata");
        return new TypeMetadata();
    }
    
    public boolean isTrackSizes() {
        return trackSizes;
    }
    
    public void setTrackSizes(boolean trackSizes) {
        this.trackSizes = trackSizes;
    }
    
    public void setTypeMetadata(TypeMetadata typeMetadata) {
        this.typeMetadata = typeMetadata;
    }
    
    public CompositeMetadata getCompositeMetadata() {
        return compositeMetadata;
    }
    
    public void setCompositeMetadata(CompositeMetadata compositeMetadata) {
        this.compositeMetadata = compositeMetadata;
    }
    
    public DocumentSerialization.ReturnType getReturnType() {
        return returnType;
    }
    
    public void setReturnType(DocumentSerialization.ReturnType returnType) {
        this.returnType = returnType;
    }
    
    public boolean isReducedResponse() {
        return reducedResponse;
    }
    
    public void setReducedResponse(boolean reducedResponse) {
        this.reducedResponse = reducedResponse;
    }
    
    public Predicate<Key> getFieldIndexKeyDataTypeFilter() {
        return this.fieldIndexKeyDataTypeFilter;
    }
    
    public Predicate<Key> getEventEntryKeyDataTypeFilter() {
        return this.eventEntryKeyDataTypeFilter;
    }
    
    public boolean isFullTableScanOnly() {
        return fullTableScanOnly;
    }
    
    public void setFullTableScanOnly(boolean fullTableScanOnly) {
        this.fullTableScanOnly = fullTableScanOnly;
    }
    
    public boolean isIncludeGroupingContext() {
        return includeGroupingContext;
    }
    
    public void setIncludeGroupingContext(boolean includeGroupingContext) {
        this.includeGroupingContext = includeGroupingContext;
    }
    
    public boolean isIncludeRecordId() {
        return includeRecordId;
    }
    
    public void setIncludeRecordId(boolean includeRecordId) {
        this.includeRecordId = includeRecordId;
    }
    
    public JexlArithmetic getArithmetic() {
        return arithmetic;
    }
    
    public void setArithmetic(JexlArithmetic arithmetic) {
        this.arithmetic = arithmetic;
    }
    
    public EventDataQueryFilter getEvaluationFilter() {
        return evaluationFilter;
    }
    
    public void setEvaluationFilter(EventDataQueryFilter evaluationFilter) {
        this.evaluationFilter = evaluationFilter;
    }
    
    public TimeFilter getTimeFilter() {
        return timeFilter;
    }
    
    public void setTimeFilter(TimeFilter timeFilter) {
        this.timeFilter = timeFilter;
    }
    
    public Map<String,Set<String>> getNonIndexedDataTypeMap() {
        return nonIndexedDataTypeMap;
    }
    
    public void setNonIndexedDataTypeMap(Map<String,Set<String>> nonIndexedDataTypeMap) {
        this.nonIndexedDataTypeMap = nonIndexedDataTypeMap;
    }
    
    public Set<String> getIndexOnlyFields() {
        return this.indexOnlyFields;
    }
    
    public Set<String> getAllIndexOnlyFields() {
        Set<String> allIndexOnlyFields = new HashSet<String>();
        // index only fields are by definition not in the event
        if (indexOnlyFields != null)
            allIndexOnlyFields.addAll(indexOnlyFields);
        // composite fields are index only as well
        if (compositeMetadata != null)
            allIndexOnlyFields.addAll(compositeMetadata.keySet());
        return allIndexOnlyFields;
    }
    
    /**
     * Get the fields that contain data that may not be in the event
     *
     * @return
     */
    public Set<String> getNonEventFields() {
        Set<String> nonEventFields = new HashSet<String>();
        // index only fields are by definition not in the event
        if (indexOnlyFields != null)
            nonEventFields.addAll(indexOnlyFields);
        // term frequency fields contain forms of the data (tokens) that are not in the event in the same form
        if (termFrequencyFields != null)
            nonEventFields.addAll(termFrequencyFields);
        // composite metadata contains combined fields that are not in the event in the same form
        if (compositeMetadata != null)
            nonEventFields.addAll(compositeMetadata.keySet());
        return nonEventFields;
    }
    
    public boolean isContainsIndexOnlyTerms() {
        return containsIndexOnlyTerms;
    }
    
    public void setContainsIndexOnlyTerms(boolean containsIndexOnlyTerms) {
        this.containsIndexOnlyTerms = containsIndexOnlyTerms;
    }
    
    public boolean isAllowFieldIndexEvaluation() {
        return allowFieldIndexEvaluation;
    }
    
    public void setAllowFieldIndexEvaluation(boolean allowFieldIndexEvaluation) {
        this.allowFieldIndexEvaluation = allowFieldIndexEvaluation;
    }
    
    public boolean isAllowTermFrequencyLookup() {
        return allowTermFrequencyLookup;
    }
    
    public void setAllowTermFrequencyLookup(boolean allowTermFrequencyLookup) {
        this.allowTermFrequencyLookup = allowTermFrequencyLookup;
    }
    
    public String getHdfsSiteConfigURLs() {
        return hdfsSiteConfigURLs;
    }
    
    public void setHdfsSiteConfigURLs(String hadoopConfigURLs) {
        this.hdfsSiteConfigURLs = hadoopConfigURLs;
    }
    
    public FileSystemCache getFileSystemCache() throws MalformedURLException {
        if (this.fsCache == null && this.hdfsSiteConfigURLs != null) {
            this.fsCache = new FileSystemCache(this.hdfsSiteConfigURLs);
        }
        return this.fsCache;
    }
    
    public QueryLock getQueryLock() throws MalformedURLException, ConfigException {
        return new QueryLock.Builder().forQueryId(getQueryId()).forFSCache(getFileSystemCache()).forIvaratorDirs(getIvaratorCacheBaseURIAlternatives())
                        .forZookeeper(getZookeeperConfig(), HdfsBackedControl.CANCELLED_CHECK_INTERVAL * 2).build();
    }
    
    public String getHdfsFileCompressionCodec() {
        return hdfsFileCompressionCodec;
    }
    
    public void setHdfsFileCompressionCodec(String hdfsFileCompressionCodec) {
        this.hdfsFileCompressionCodec = hdfsFileCompressionCodec;
    }
    
    public String getZookeeperConfig() {
        return zookeeperConfig;
    }
    
    public void setZookeeperConfig(String zookeeperConfig) {
        this.zookeeperConfig = zookeeperConfig;
    }
    
    public List<String> getIvaratorCacheBaseURIsAsList() {
        return ivaratorCacheBaseURIAlternatives;
    }
    
    public String getIvaratorCacheBaseURIAlternatives() {
        if (ivaratorCacheBaseURIAlternatives == null) {
            return null;
        } else {
            StringBuilder builder = new StringBuilder();
            for (String hdfsCacheBaseURI : ivaratorCacheBaseURIAlternatives) {
                if (builder.length() > 0) {
                    builder.append(',');
                }
                builder.append(hdfsCacheBaseURI);
            }
            return builder.toString();
        }
    }
    
    public void setIvaratorCacheBaseURIAlternatives(String ivaratorCacheBaseURIAlternatives) {
        if (ivaratorCacheBaseURIAlternatives == null || ivaratorCacheBaseURIAlternatives.isEmpty()) {
            this.ivaratorCacheBaseURIAlternatives = null;
        } else {
            this.ivaratorCacheBaseURIAlternatives = Arrays.asList(StringUtils.split(ivaratorCacheBaseURIAlternatives, ','));
        }
    }
    
    public int getIvaratorCacheBufferSize() {
        return ivaratorCacheBufferSize;
    }
    
    public void setIvaratorCacheBufferSize(int ivaratorCacheBufferSize) {
        this.ivaratorCacheBufferSize = ivaratorCacheBufferSize;
    }
    
    public long getIvaratorCacheScanPersistThreshold() {
        return ivaratorCacheScanPersistThreshold;
    }
    
    public void setIvaratorCacheScanPersistThreshold(long ivaratorCacheScanPersistThreshold) {
        this.ivaratorCacheScanPersistThreshold = ivaratorCacheScanPersistThreshold;
    }
    
    public long getIvaratorCacheScanTimeout() {
        return ivaratorCacheScanTimeout;
    }
    
    public void setIvaratorCacheScanTimeout(long ivaratorCacheScanTimeout) {
        this.ivaratorCacheScanTimeout = ivaratorCacheScanTimeout;
    }
    
    public int getMaxIndexRangeSplit() {
        return maxIndexRangeSplit;
    }
    
    public void setMaxIndexRangeSplit(int maxIndexRangeSplit) {
        this.maxIndexRangeSplit = maxIndexRangeSplit;
    }
    
    public int getIvaratorMaxOpenFiles() {
        return ivaratorMaxOpenFiles;
    }
    
    public void setIvaratorMaxOpenFiles(int ivaratorMaxOpenFiles) {
        this.ivaratorMaxOpenFiles = ivaratorMaxOpenFiles;
    }
    
    public int getMaxIvaratorSources() {
        return maxIvaratorSources;
    }
    
    public void setMaxIvaratorSources(int maxIvaratorSources) {
        this.maxIvaratorSources = maxIvaratorSources;
    }
    
    public boolean isCompressResults() {
        return compressResults;
    }
    
    public void setCompressResults(boolean compressResults) {
        this.compressResults = compressResults;
    }
    
    public Map<String,Integer> getLimitFieldsMap() {
        return limitFieldsMap;
    }
    
    public void setLimitFieldsMap(Map<String,Integer> limitFieldsMap) {
        this.limitFieldsMap = limitFieldsMap;
    }
    
    public boolean isLimitFieldsPreQueryEvaluation() {
        return limitFieldsPreQueryEvaluation;
    }
    
    public void setLimitFieldsPreQueryEvaluation(boolean limitFieldsPreQueryEvaluation) {
        this.limitFieldsPreQueryEvaluation = limitFieldsPreQueryEvaluation;
    }
    
    public String getLimitFieldsField() {
        return limitFieldsField;
    }
    
    public void setLimitFieldsField(String limitFieldsField) {
        this.limitFieldsField = limitFieldsField;
    }
    
    public Set<String> getGroupFieldsMap() {
        return groupFieldsSet;
    }
    
    public void setGroupFieldsMap(Set<String> groupFieldsSet) {
        this.groupFieldsSet = groupFieldsSet;
    }
    
    public Set<String> getHitsOnlySet() {
        return hitsOnlySet;
    }
    
    public void setHitsOnlySet(Set<String> hitsOnlySet) {
        this.hitsOnlySet = hitsOnlySet;
    }
    
    public boolean isDateIndexTimeTravel() {
        return dateIndexTimeTravel;
    }
    
    public void setDateIndexTimeTravel(boolean dateIndexTimeTravel) {
        this.dateIndexTimeTravel = dateIndexTimeTravel;
    }
    
    public boolean isSortedUIDs() {
        return sortedUIDs;
    }
    
    public void setSortedUIDs(boolean sortedUIDs) {
        this.sortedUIDs = sortedUIDs;
    }
    
    public boolean isDebugMultithreadedSources() {
        return debugMultithreadedSources;
    }
    
    public void setDebugMultithreadedSources(boolean debugMultithreadedSources) {
        this.debugMultithreadedSources = debugMultithreadedSources;
    }
    
    public boolean isDataQueryExpressionFilterEnabled() {
        return dataQueryExpressionFilterEnabled;
    }
    
    public void setDataQueryExpressionFilterEnabled(boolean dataQueryExpressionFilterEnabled) {
        this.dataQueryExpressionFilterEnabled = dataQueryExpressionFilterEnabled;
    }
    
    @Override
    public IteratorOptions describeOptions() {
        Map<String,String> options = new HashMap<>();
        
        options.put(DISABLE_EVALUATION, "If provided, JEXL evaluation is not performed against any document.");
        options.put(DISABLE_FIELD_INDEX_EVAL,
                        "If provided, a query tree is not evaluated against the field index. Only used in the case of doc specific ranges");
        options.put(LIMIT_OVERRIDE, "If provided, we will not assume the FI ranges can be constructed from the query");
        options.put(LIMIT_SOURCES, "Allows client to limit the number of sources used for this scan");
        options.put(DISABLE_DOCUMENTS_WITHOUT_EVENTS, "Removes documents in which only hits against the index were found, and no event");
        options.put(QUERY, "The JEXL query to evaluate documents against");
        options.put(QUERY_ID, "The UUID of the query");
        options.put(TYPE_METADATA, "A mapping of field name to a set of DataType class names");
        options.put(METADATA_TABLE_NAME, "The name of the metadata table");
        options.put(QUERY_MAPPING_COMPRESS, "Boolean value to indicate Normalizer mapping is compressed");
        options.put(REDUCED_RESPONSE, "Whether or not to return visibility markings on each attribute. Default: " + reducedResponse);
        options.put(Constants.RETURN_TYPE, "The method to use to serialize data for return to the client");
        options.put(FULL_TABLE_SCAN_ONLY, "If true, do not perform boolean logic, just scan the documents");
        options.put(PROJECTION_FIELDS, "Attributes to return to the client");
        options.put(BLACKLISTED_FIELDS, "Attributes to *not* return to the client");
        options.put(FILTER_MASKED_VALUES, "Filter the masked values when both the masked and unmasked variants are in the result set.");
        options.put(INCLUDE_DATATYPE, "Include the data type as a field in the document.");
        options.put(INCLUDE_RECORD_ID, "Include the record id as a field in the document.");
        options.put(COLLECT_TIMING_DETAILS, "Collect timing details about the underlying iterators");
        options.put(STATSD_HOST_COLON_PORT,
                        "A configured statsd host:port which will be used to send resource and timing details from the underlying iterators if configured");
        options.put(STATSD_MAX_QUEUE_SIZE, "Max queued metrics before statsd metrics are flushed");
        options.put(INCLUDE_HIERARCHY_FIELDS, "Include the hierarchy fields (CHILD_COUNT and PARENT_UID) as document fields.");
        options.put(DATATYPE_FIELDNAME, "The field name to use when inserting the fieldname into the document.");
        options.put(DATATYPE_FILTER, "CSV of data type names that should be included when scanning.");
        options.put(INDEX_ONLY_FIELDS, "The serialized collection of field names that only occur in the index");
        options.put(COMPOSITE_FIELDS, "The serialized collection of field names that make up composites");
        options.put(START_TIME, "The start time for this query in milliseconds");
        options.put(END_TIME, "The end time for this query in milliseconds");
        options.put(POSTPROCESSING_CLASSES, "CSV of functions and predicates to apply to documents that pass the original query.");
        options.put(IndexIterator.INDEX_FILTERING_CLASSES, "CSV of predicates to apply to keys that pass the original field index (fi) scan.");
        options.put(INCLUDE_GROUPING_CONTEXT, "Keep the grouping context on the final returned document");
        options.put(LIMIT_FIELDS, "limit fields");
        options.put(GROUP_FIELDS, "group fields");
        options.put(HIT_LIST, "hit list");
        options.put(NON_INDEXED_DATATYPES, "Normalizers to apply only at aggregation time");
        options.put(CONTAINS_INDEX_ONLY_TERMS, "Does the query being evaluated contain any terms which are index-only");
        options.put(ALLOW_FIELD_INDEX_EVALUATION,
                        "Allow the evaluation to occur purely on values pulled from the field index for queries only accessing indexed fields (default is true)");
        options.put(ALLOW_TERM_FREQUENCY_LOOKUP, "Allow the evaluation to use the term frequencies in lieu of the field index when appropriate");
        options.put(TERM_FREQUENCIES_REQUIRED, "Does the query require gathering term frequencies");
        options.put(TERM_FREQUENCY_FIELDS, "comma-delimited list of fields that contain term frequencies");
        options.put(CONTENT_EXPANSION_FIELDS, "comma-delimited list of fields used for content function expansions");
        options.put(HDFS_SITE_CONFIG_URLS, "URLs (comma delimited) of where to find the hadoop hdfs and core site configuration files");
        options.put(HDFS_FILE_COMPRESSION_CODEC, "A hadoop compression codec to use for files if supported");
        options.put(IVARATOR_CACHE_BASE_URI_ALTERNATIVES,
                        "A list of URIs of where all query's caches are to be located for ivarators (caching field index iterators)");
        options.put(IVARATOR_CACHE_BUFFER_SIZE, "The size of the hdfs cache buffer size (items held in memory before dumping to hdfs).  Default is 10000.");
        options.put(IVARATOR_SCAN_PERSIST_THRESHOLD,
                        "The number of underlying field index keys scanned before the hdfs cache buffer is forced to persist).  Default is 100000.");
        options.put(IVARATOR_SCAN_TIMEOUT, "The time after which the hdfs cache buffer is forced to persist.  Default is 60 minutes.");
        options.put(MAX_INDEX_RANGE_SPLIT,
                        "The maximum number of ranges to split a field index scan (ivarator) range into for multithreading.  Note the thread pool size is controlled via an accumulo property.");
        options.put(MAX_IVARATOR_OPEN_FILES,
                        "The maximum number of files that can be opened at one time during a merge sort.  If more that this number of files are created, then compactions will occur");
        options.put(MAX_IVARATOR_SOURCES,
                        " The maximum number of sources to use for ivarators across all ivarated terms within the query.  Note the thread pool size is controlled via an accumulo property.");
        options.put(YIELD_THRESHOLD_MS,
                        "The threshold in milliseconds that the query iterator will evaluate consecutive documents to false before yielding the scan.");
        options.put(COMPRESS_SERVER_SIDE_RESULTS, "GZIP compress the serialized Documents before returning to the webserver");
        options.put(MAX_EVALUATION_PIPELINES, "The max number of evaluation pipelines");
        options.put(SERIAL_EVALUATION_PIPELINE, "Forces us to use the serial pipeline. Allows us to still have a single thread for evaluation");
        options.put(MAX_PIPELINE_CACHED_RESULTS, "The max number of non-null evaluated results to cache beyond the evaluation pipelines in queue");
        options.put(DATE_INDEX_TIME_TRAVEL, "Whether the shards from before the event should be gathered from the dateIndex");
        
        options.put(SORTED_UIDS,
                        "Whether the UIDs need to be sorted.  Normally this is true, however in limited circumstances it could be false which allows ivarators to avoid pre-fetching all UIDs and sorting before returning the first one.");
        
        options.put(DEBUG_MULTITHREADED_SOURCES, "If provided, the SourceThreadTrackingIterator will be used");
        options.put(DATA_QUERY_EXPRESSION_FILTER_ENABLED, "If true, the EventDataQueryExpression filter will be used when performing TLD queries");
        
        options.put(METADATA_TABLE_NAME, this.metadataTableName);
        options.put(LIMIT_FIELDS_PRE_QUERY_EVALUATION, "If true, non-query fields limits will be applied immediately off the iterator");
        options.put(LIMIT_FIELDS_FIELD, "When " + LIMIT_FIELDS_PRE_QUERY_EVALUATION
                        + " is set to true this field will contain all fields that were limited immediately");
        
        return new IteratorOptions(getClass().getSimpleName(), "Runs a query against the DATAWAVE tables", options, null);
    }
    
    @Override
    public boolean validateOptions(Map<String,String> options) {
        if (log.isTraceEnabled()) {
            log.trace("Options: " + options);
        }
        
        this.options = options;
        
        // If we don't have a query, make sure it's because
        // we don't aren't performing any Jexl evaluation
        if (options.containsKey(DISABLE_EVALUATION)) {
            this.disableEvaluation = Boolean.parseBoolean(options.get(DISABLE_EVALUATION));
        }
        
        if (options.containsKey(DISABLE_FIELD_INDEX_EVAL)) {
            this.disableFiEval = Boolean.parseBoolean(options.get(DISABLE_FIELD_INDEX_EVAL));
        }
        
        if (options.containsKey(LIMIT_OVERRIDE)) {
            this.limitOverride = Boolean.parseBoolean(options.get(LIMIT_OVERRIDE));
        }
        
        if (options.containsKey(LIMIT_SOURCES)) {
            try {
                this.sourceLimit = Long.valueOf(options.get(LIMIT_SOURCES));
            } catch (NumberFormatException nfe) {
                this.sourceLimit = -1;
            }
        }
        
        if (options.containsKey(DISABLE_DOCUMENTS_WITHOUT_EVENTS)) {
            this.disableIndexOnlyDocuments = Boolean.parseBoolean(options.get(DISABLE_DOCUMENTS_WITHOUT_EVENTS));
        }
        
        // If we're not provided a query, we may not be performing any
        // evaluation
        if (options.containsKey(QUERY)) {
            this.query = options.get(QUERY);
        } else if (!this.disableEvaluation) {
            log.error("If a query is not specified, evaluation must be disabled.");
            return false;
        }
        
        if (options.containsKey(QUERY_ID)) {
            this.queryId = options.get(QUERY_ID);
        }
        
        if (options.containsKey(SCAN_ID)) {
            this.scanId = options.get(SCAN_ID);
        }
        
        if (options.containsKey(QUERY_MAPPING_COMPRESS)) {
            compressedMappings = Boolean.valueOf(options.get(QUERY_MAPPING_COMPRESS));
        }
        
        this.validateTypeMetadata(options);
        
        if (options.containsKey(COMPOSITE_METADATA)) {
            try {
                String compositeMetadataString = options.get(COMPOSITE_METADATA);
                if (compressedMappings) {
                    compositeMetadataString = decompressOption(compositeMetadataString, QueryOptions.UTF8);
                }
                this.compositeMetadata = buildCompositeMetadata(compositeMetadataString);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            
            if (log.isTraceEnabled()) {
                log.trace("Using compositeMetadata: " + this.compositeMetadata);
            }
        }
        
        // Currently writable, kryo or toString
        if (options.containsKey(Constants.RETURN_TYPE)) {
            setReturnType(DocumentSerialization.ReturnType.valueOf(options.get(Constants.RETURN_TYPE)));
        }
        
        // Boolean: should each attribute maintain a ColumnVisibility.
        if (options.containsKey(REDUCED_RESPONSE)) {
            setReducedResponse(Boolean.parseBoolean(options.get(REDUCED_RESPONSE)));
        }
        
        if (options.containsKey(FULL_TABLE_SCAN_ONLY)) {
            setFullTableScanOnly(Boolean.parseBoolean(options.get(FULL_TABLE_SCAN_ONLY)));
        }
        
        if (options.containsKey(TRACK_SIZES) && options.get(TRACK_SIZES) != null) {
            setTrackSizes(Boolean.parseBoolean(options.get(TRACK_SIZES)));
        }
        
        if (options.containsKey(PROJECTION_FIELDS)) {
            this.projectResults = true;
            this.useWhiteListedFields = true;
            
            String fieldList = options.get(PROJECTION_FIELDS);
            if (fieldList != null && EVERYTHING.equals(PROJECTION_FIELDS)) {
                this.whiteListedFields = PowerSet.instance();
            } else if (fieldList != null && !fieldList.trim().equals("")) {
                this.whiteListedFields = new HashSet<>();
                Collections.addAll(this.whiteListedFields, StringUtils.split(fieldList, Constants.PARAM_VALUE_SEP));
            }
            if (options.containsKey(HIT_LIST) && Boolean.parseBoolean(options.get(HIT_LIST))) {
                this.whiteListedFields.add("HIT_TERM");
            }
        }
        
        if (options.containsKey(BLACKLISTED_FIELDS)) {
            if (this.projectResults) {
                log.error("QueryOptions.PROJECTION_FIELDS and QueryOptions.BLACKLISTED_FIELDS are mutually exclusive");
                return false;
            }
            
            this.projectResults = true;
            this.useBlackListedFields = true;
            
            String fieldList = options.get(BLACKLISTED_FIELDS);
            if (fieldList != null && !fieldList.trim().equals("")) {
                this.blackListedFields = new HashSet<>();
                Collections.addAll(this.blackListedFields, StringUtils.split(fieldList, Constants.PARAM_VALUE_SEP));
            }
        }
        
        // log.info("Performing regular query : queryId=" + this.queryId);
        
        this.equality = new PrefixEquality(PartialKey.ROW_COLFAM);
        this.evaluationFilter = null;
        this.getDocumentKey = GetStartKey.instance();
        this.mustUseFieldIndex = false;
        
        if (options.containsKey(FILTER_MASKED_VALUES)) {
            this.filterMaskedValues = Boolean.parseBoolean(options.get(FILTER_MASKED_VALUES));
        }
        
        if (options.containsKey(INCLUDE_DATATYPE)) {
            this.includeDatatype = Boolean.parseBoolean(options.get(INCLUDE_DATATYPE));
            if (this.includeDatatype) {
                this.datatypeKey = options.containsKey(DATATYPE_FIELDNAME) ? options.get(DATATYPE_FIELDNAME) : DEFAULT_DATATYPE_FIELDNAME;
            }
        }
        
        if (options.containsKey(INCLUDE_RECORD_ID)) {
            this.includeRecordId = Boolean.parseBoolean(options.get(INCLUDE_RECORD_ID));
        }
        
        if (options.containsKey(COLLECT_TIMING_DETAILS)) {
            this.collectTimingDetails = Boolean.parseBoolean(options.get(COLLECT_TIMING_DETAILS));
        }
        
        if (options.containsKey(STATSD_HOST_COLON_PORT)) {
            this.statsdHostAndPort = options.get(STATSD_HOST_COLON_PORT);
        }
        
        if (options.containsKey(STATSD_MAX_QUEUE_SIZE)) {
            this.statsdMaxQueueSize = Integer.parseInt(options.get(STATSD_MAX_QUEUE_SIZE));
        }
        
        if (options.containsKey(INCLUDE_HIERARCHY_FIELDS)) {
            this.includeHierarchyFields = Boolean.parseBoolean(options.get(INCLUDE_HIERARCHY_FIELDS));
        }
        
        if (options.containsKey(DATATYPE_FILTER)) {
            String filterCsv = options.get(DATATYPE_FILTER);
            if (filterCsv != null && !filterCsv.isEmpty()) {
                HashSet<String> set = Sets.newHashSet(StringUtils.split(filterCsv, ','));
                
                Iterable<Text> tformed = Iterables.transform(set, new StringToText());
                if (options.containsKey(SeekingQueryPlanner.MAX_KEYS_BEFORE_DATATYPE_SEEK)) {
                    this.fieldIndexKeyDataTypeFilter = new FieldIndexKeyDataTypeFilter(tformed, Integer.parseInt(options
                                    .get(SeekingQueryPlanner.MAX_KEYS_BEFORE_DATATYPE_SEEK)));
                } else {
                    this.fieldIndexKeyDataTypeFilter = new FieldIndexKeyDataTypeFilter(tformed);
                }
                this.eventEntryKeyDataTypeFilter = new EventKeyDataTypeFilter(tformed);
            } else {
                this.fieldIndexKeyDataTypeFilter = KeyIdentity.Function;
                this.eventEntryKeyDataTypeFilter = KeyIdentity.Function;
            }
        } else {
            this.fieldIndexKeyDataTypeFilter = KeyIdentity.Function;
            this.eventEntryKeyDataTypeFilter = KeyIdentity.Function;
        }
        
        if (options.containsKey(INDEX_ONLY_FIELDS)) {
            this.indexOnlyFields = buildIndexOnlyFieldsSet(options.get(INDEX_ONLY_FIELDS));
        } else if (!this.fullTableScanOnly) {
            log.error("A list of index only fields must be provided when running an optimized query");
            return false;
        }
        
        if (options.containsKey(COMPOSITE_METADATA)) {
            this.compositeMetadata = buildCompositeMetadata(options.get(COMPOSITE_METADATA));
        }
        this.fiAggregator = new IdentityAggregator(getAllIndexOnlyFields(), getEvaluationFilter(), getEvaluationFilter() != null ? getEvaluationFilter()
                        .getMaxNextCount() : -1);
        
        if (options.containsKey(IGNORE_COLUMN_FAMILIES)) {
            this.ignoreColumnFamilies = buildIgnoredColumnFamilies(options.get(IGNORE_COLUMN_FAMILIES));
        }
        
        if (options.containsKey(START_TIME)) {
            this.startTime = Long.parseLong(options.get(START_TIME));
        } else {
            log.error("Must pass a value for " + START_TIME);
            return false;
        }
        
        if (options.containsKey(END_TIME)) {
            this.endTime = Long.parseLong(options.get(END_TIME));
        } else {
            log.error("Must pass a value for " + END_TIME);
            return false;
        }
        
        if (this.endTime < this.startTime) {
            log.error("The startTime was greater than the endTime: " + this.startTime + " > " + this.endTime);
            return false;
        }
        
        this.timeFilter = new TimeFilter(startTime, endTime);
        
        if (options.containsKey(INCLUDE_GROUPING_CONTEXT)) {
            this.setIncludeGroupingContext(Boolean.parseBoolean(options.get(INCLUDE_GROUPING_CONTEXT)));
        }
        
        if (options.containsKey(LIMIT_FIELDS)) {
            String limitFields = options.get(LIMIT_FIELDS);
            for (String paramGroup : Splitter.on(',').omitEmptyStrings().trimResults().split(limitFields)) {
                String[] keyAndValue = Iterables.toArray(Splitter.on('=').omitEmptyStrings().trimResults().split(paramGroup), String.class);
                if (keyAndValue != null && keyAndValue.length > 1) {
                    this.getLimitFieldsMap().put(keyAndValue[0], Integer.parseInt(keyAndValue[1]));
                }
            }
        }
        
        if (options.containsKey(LIMIT_FIELDS_PRE_QUERY_EVALUATION)) {
            this.setLimitFieldsPreQueryEvaluation(Boolean.parseBoolean(options.get(LIMIT_FIELDS_PRE_QUERY_EVALUATION)));
        }
        
        if (options.containsKey(LIMIT_FIELDS_FIELD)) {
            this.setLimitFieldsField(options.get(LIMIT_FIELDS_FIELD));
        }
        
        if (options.containsKey(GROUP_FIELDS)) {
            String groupFields = options.get(GROUP_FIELDS);
            for (String param : Splitter.on(',').omitEmptyStrings().trimResults().split(groupFields)) {
                this.getGroupFieldsMap().add(param);
            }
        }
        
        if (options.containsKey(HIT_LIST)) {
            log.debug("Adding hitList to QueryOptions? " + options.get(HIT_LIST));
            if (Boolean.parseBoolean(options.get(HIT_LIST))) {
                this.setArithmetic(new HitListArithmetic());
            }
        } else {
            log.debug("options does not include key 'hit.list'");
        }
        
        if (options.containsKey(DATE_INDEX_TIME_TRAVEL)) {
            log.debug("Adding dateIndexTimeTravel to QueryOptions? " + options.get(DATE_INDEX_TIME_TRAVEL));
            boolean dateIndexTimeTravel = Boolean.parseBoolean(options.get(DATE_INDEX_TIME_TRAVEL));
            if (dateIndexTimeTravel) {
                this.setDateIndexTimeTravel(dateIndexTimeTravel);
            }
        }
        
        if (options.containsKey(POSTPROCESSING_CLASSES)) {
            this.postProcessingFunctions = options.get(POSTPROCESSING_CLASSES);
            // test parsing of the functions
            getPostProcessingChain(new WrappingIterator<Entry<Key,Document>>());
        }
        
        if (options.containsKey(NON_INDEXED_DATATYPES)) {
            try {
                
                String nonIndexedDataTypes = options.get(NON_INDEXED_DATATYPES);
                if (compressedMappings) {
                    nonIndexedDataTypes = decompressOption(nonIndexedDataTypes, QueryOptions.UTF8);
                }
                
                this.setNonIndexedDataTypeMap(buildFieldDataTypeMap(nonIndexedDataTypes));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        
        if (options.containsKey(CONTAINS_INDEX_ONLY_TERMS)) {
            this.setContainsIndexOnlyTerms(Boolean.parseBoolean(options.get(CONTAINS_INDEX_ONLY_TERMS)));
        }
        
        if (options.containsKey(ALLOW_FIELD_INDEX_EVALUATION)) {
            this.setAllowFieldIndexEvaluation(Boolean.parseBoolean(options.get(ALLOW_FIELD_INDEX_EVALUATION)));
        }
        
        if (options.containsKey(ALLOW_TERM_FREQUENCY_LOOKUP)) {
            this.setAllowTermFrequencyLookup(Boolean.parseBoolean(options.get(ALLOW_TERM_FREQUENCY_LOOKUP)));
        }
        
        if (options.containsKey(HDFS_SITE_CONFIG_URLS)) {
            this.setHdfsSiteConfigURLs(options.get(HDFS_SITE_CONFIG_URLS));
        }
        
        if (options.containsKey(HDFS_FILE_COMPRESSION_CODEC)) {
            this.setHdfsFileCompressionCodec(options.get(HDFS_FILE_COMPRESSION_CODEC));
        }
        
        if (options.containsKey(ZOOKEEPER_CONFIG)) {
            this.setZookeeperConfig(options.get(ZOOKEEPER_CONFIG));
        }
        
        if (options.containsKey(IVARATOR_CACHE_BASE_URI_ALTERNATIVES)) {
            this.setIvaratorCacheBaseURIAlternatives(options.get(IVARATOR_CACHE_BASE_URI_ALTERNATIVES));
        }
        
        if (options.containsKey(IVARATOR_CACHE_BUFFER_SIZE)) {
            this.setIvaratorCacheBufferSize(Integer.parseInt(options.get(IVARATOR_CACHE_BUFFER_SIZE)));
        }
        
        if (options.containsKey(IVARATOR_SCAN_PERSIST_THRESHOLD)) {
            this.setIvaratorCacheScanPersistThreshold(Long.parseLong(options.get(IVARATOR_SCAN_PERSIST_THRESHOLD)));
        }
        
        if (options.containsKey(IVARATOR_SCAN_TIMEOUT)) {
            this.setIvaratorCacheScanTimeout(Long.parseLong(options.get(IVARATOR_SCAN_TIMEOUT)));
        }
        
        if (options.containsKey(MAX_INDEX_RANGE_SPLIT)) {
            this.setMaxIndexRangeSplit(Integer.parseInt(options.get(MAX_INDEX_RANGE_SPLIT)));
        }
        
        if (options.containsKey(MAX_IVARATOR_OPEN_FILES)) {
            this.setIvaratorMaxOpenFiles(Integer.parseInt(options.get(MAX_IVARATOR_OPEN_FILES)));
        }
        
        if (options.containsKey(MAX_IVARATOR_SOURCES)) {
            this.setMaxIvaratorSources(Integer.parseInt(options.get(MAX_IVARATOR_SOURCES)));
        }
        
        if (options.containsKey(YIELD_THRESHOLD_MS)) {
            this.setYieldThresholdMs(Long.parseLong(options.get(YIELD_THRESHOLD_MS)));
        }
        
        if (options.containsKey(COMPRESS_SERVER_SIDE_RESULTS)) {
            this.setCompressResults(Boolean.parseBoolean(options.get(COMPRESS_SERVER_SIDE_RESULTS)));
        }
        
        if (options.containsKey(MAX_EVALUATION_PIPELINES)) {
            this.setMaxEvaluationPipelines(Integer.parseInt(options.get(MAX_EVALUATION_PIPELINES)));
        }
        
        if (options.containsKey(SERIAL_EVALUATION_PIPELINE)) {
            this.setSerialEvaluationPipeline(Boolean.parseBoolean(options.get(SERIAL_EVALUATION_PIPELINE)));
        }
        
        if (options.containsKey(MAX_PIPELINE_CACHED_RESULTS)) {
            this.setMaxPipelineCachedResults(Integer.parseInt(options.get(MAX_PIPELINE_CACHED_RESULTS)));
        }
        
        if (options.containsKey(TERM_FREQUENCIES_REQUIRED)) {
            this.setTermFrequenciesRequired(Boolean.parseBoolean(options.get(TERM_FREQUENCIES_REQUIRED)));
        }
        this.setTermFrequencyFields(parseTermFrequencyFields(options));
        this.setContentExpansionFields(parseContentExpansionFields(options));
        
        if (options.containsKey(BATCHED_QUERY)) {
            this.batchedQueries = Integer.parseInt(options.get(BATCHED_QUERY));
            
            if (this.batchedQueries > 0) {
                
                // override query options since this is a mismatch of options
                // combining is only meant to be used when threading is enabled
                if (maxEvaluationPipelines == 1)
                    maxEvaluationPipelines = 2;
                
                batchStack = Queues.newArrayDeque();
                for (int i = 0; i < batchedQueries; i++) {
                    String rangeValue = options.get(BATCHED_QUERY_RANGE_PREFIX + i);
                    String queryValue = options.get(BATCHED_QUERY_PREFIX + i);
                    if (null != rangeValue && null != queryValue) {
                        try {
                            Range decodedRange = ColumnRangeIterator.decodeRange(rangeValue);
                            if (log.isTraceEnabled()) {
                                log.trace("Adding batch " + decodedRange + " " + queryValue);
                            }
                            batchStack.offer(Maps.immutableEntry(decodedRange, queryValue));
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    }
                }
            }
        }
        
        if (options.containsKey(DATE_INDEX_TIME_TRAVEL)) {
            this.dateIndexTimeTravel = Boolean.parseBoolean(options.get(DATE_INDEX_TIME_TRAVEL));
        }
        
        if (options.containsKey(SORTED_UIDS)) {
            this.sortedUIDs = Boolean.parseBoolean(options.get(SORTED_UIDS));
        }
        
        if (options.containsKey(DEBUG_MULTITHREADED_SOURCES)) {
            this.debugMultithreadedSources = Boolean.parseBoolean(options.get(DEBUG_MULTITHREADED_SOURCES));
        }
        
        if (options.containsKey(DATA_QUERY_EXPRESSION_FILTER_ENABLED)) {
            this.dataQueryExpressionFilterEnabled = Boolean.parseBoolean(options.get(DATA_QUERY_EXPRESSION_FILTER_ENABLED));
        }
        
        return true;
    }
    
    private void setSerialEvaluationPipeline(boolean serialEvaluationPipeline) {
        this.serialEvaluationPipeline = serialEvaluationPipeline;
    }
    
    protected void validateTypeMetadata(Map<String,String> options) {
        if (options.containsKey(TYPE_METADATA_AUTHS)) {
            String typeMetadataAuthsString = options.get(TYPE_METADATA_AUTHS);
            try {
                if (typeMetadataAuthsString != null && compressedMappings) {
                    typeMetadataAuthsString = decompressOption(typeMetadataAuthsString, QueryOptions.UTF8);
                }
                this.typeMetadataAuthsKey = Sets.newHashSet(Splitter.on(CharMatcher.anyOf(",& ")).omitEmptyStrings().trimResults()
                                .split(typeMetadataAuthsString));
            } catch (IOException e) {
                log.warn("could not set typeMetadataAuthsKey from: \"" + typeMetadataAuthsString + "\"");
            }
            
            if (log.isTraceEnabled()) {
                log.trace("Using typeMetadataAuthsKey: " + this.typeMetadataAuthsKey);
            }
        }
        // Serialized version of a mapping from field name to DataType used
        if (options.containsKey(TYPE_METADATA)) {
            String typeMetadataString = options.get(TYPE_METADATA);
            try {
                if (compressedMappings) {
                    typeMetadataString = decompressOption(typeMetadataString, QueryOptions.UTF8);
                }
                this.typeMetadata = buildTypeMetadata(typeMetadataString);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            
            if (log.isTraceEnabled()) {
                log.trace("Using typeMetadata: " + this.typeMetadata);
            }
        }
        if (options.containsKey(METADATA_TABLE_NAME)) {
            this.metadataTableName = options.get(METADATA_TABLE_NAME);
        }
        
    }
    
    protected static String decompressOption(final String buffer, Charset characterSet) throws IOException {
        final byte[] inBase64 = Base64.decodeBase64(buffer.getBytes());
        
        ByteArrayInputStream byteInputStream = new ByteArrayInputStream(inBase64);
        
        GZIPInputStream gzipInputStream = new GZIPInputStream(byteInputStream);
        
        DataInputStream dataInputStream = new DataInputStream(gzipInputStream);
        
        final int length = dataInputStream.readInt();
        final byte[] dataBytes = new byte[length];
        dataInputStream.readFully(dataBytes, 0, length);
        
        dataInputStream.close();
        gzipInputStream.close();
        
        return new String(dataBytes, characterSet);
    }
    
    /**
     * Restore the mapping of field name to dataTypes from a String-ified representation
     *
     * @param data
     * @return
     * @throws IOException
     */
    public static Map<String,Set<String>> buildFieldDataTypeMap(String data) throws IOException {
        
        Map<String,Set<String>> mapping = new HashMap<>();
        
        if (data != null) {
            String[] entries = StringUtils.split(data, ';');
            for (String entry : entries) {
                String[] entrySplits = StringUtils.split(entry, ':');
                
                if (2 != entrySplits.length) {
                    log.warn("Skipping unparseable normalizer entry: '" + entry + "', from '" + data + "'");
                } else {
                    String[] values = StringUtils.split(entrySplits[1], ',');
                    HashSet<String> dataTypes = new HashSet<>();
                    
                    Collections.addAll(dataTypes, values);
                    
                    mapping.put(entrySplits[0], dataTypes);
                    
                    if (log.isTraceEnabled())
                        log.trace("Adding " + entrySplits[0] + " " + dataTypes);
                }
            }
        }
        
        return mapping;
    }
    
    public static Set<String> fetchDatatypeKeys(String data) throws IOException {
        Set<String> keys = Sets.newHashSet();
        if (data != null) {
            String[] entries = StringUtils.split(data, ';');
            for (String entry : entries) {
                String[] entrySplits = StringUtils.split(entry, ':');
                
                if (2 != entrySplits.length) {
                    log.warn("Skipping unparseable normalizer entry: '" + entry + "', from '" + data + "'");
                } else {
                    keys.add(entrySplits[0]);
                    
                    if (log.isTraceEnabled())
                        log.trace("Adding " + entrySplits[0] + " " + keys);
                }
            }
        }
        
        return keys;
    }
    
    public static TypeMetadata buildTypeMetadata(String data) throws IOException {
        return new TypeMetadata(data);
    }
    
    public static CompositeMetadata buildCompositeMetadata(String in) {
        return new CompositeMetadata(in);
    }
    
    /**
     * Build a String-ified version of the Map to serialize to this SKVI.
     *
     * @param map
     * @return
     */
    public static String buildFieldNormalizerString(Map<String,Set<String>> map) {
        StringBuilder sb = new StringBuilder();
        
        for (Entry<String,Set<String>> entry : map.entrySet()) {
            if (sb.length() > 0) {
                sb.append(';');
            }
            
            sb.append(entry.getKey()).append(':');
            
            boolean first = true;
            for (String val : entry.getValue()) {
                if (!first) {
                    sb.append(',');
                }
                
                sb.append(val);
                first = false;
            }
        }
        
        return sb.toString();
    }
    
    /**
     * Build a String-ified version of the Map to serialize to this SKVI.
     *
     * @param map
     * @return
     * @throws IOException
     */
    public static String buildFieldNormalizerString(Multimap<String,Type<?>> map) throws IOException {
        StringBuilder sb = new StringBuilder();
        
        for (String fieldName : map.keySet()) {
            if (sb.length() > 0) {
                sb.append(';');
            }
            
            sb.append(fieldName).append(':');
            
            boolean first = true;
            for (Type<?> type : map.get(fieldName)) {
                if (!first) {
                    sb.append(',');
                }
                
                sb.append(type.getClass().getName());
                first = false;
            }
        }
        
        return sb.toString();
    }
    
    public static String compressOption(final String data, final Charset characterSet) throws IOException {
        final ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
        final GZIPOutputStream gzipStream = new GZIPOutputStream(byteStream);
        final DataOutputStream dataOut = new DataOutputStream(gzipStream);
        
        byte[] arr = data.getBytes(characterSet);
        final int length = arr.length;
        
        dataOut.writeInt(length);
        dataOut.write(arr);
        
        dataOut.close();
        byteStream.close();
        
        return new String(Base64.encodeBase64(byteStream.toByteArray()));
    }
    
    public static String buildIndexOnlyFieldsString(Collection<String> fields) {
        StringBuilder sb = new StringBuilder();
        for (String field : fields) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            
            sb.append(field);
        }
        
        return sb.toString();
    }
    
    public static Set<String> buildIndexOnlyFieldsSet(String indexOnlyFields) {
        Set<String> fields = new HashSet<>();
        for (String indexOnlyField : StringUtils.split(indexOnlyFields, ',')) {
            if (!org.apache.commons.lang.StringUtils.isBlank(indexOnlyField)) {
                fields.add(indexOnlyField);
            }
        }
        return fields;
    }
    
    public static String buildIgnoredColumnFamiliesString(Collection<String> colFams) {
        StringBuilder sb = new StringBuilder();
        for (String cf : colFams) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            
            sb.append(cf);
        }
        
        return sb.toString();
    }
    
    public static Set<String> buildIgnoredColumnFamilies(String colFams) {
        return Sets.newHashSet(StringUtils.split(colFams, ','));
    }
    
    @SuppressWarnings({"rawtypes", "unchecked"})
    public Iterator<Entry<Key,Document>> getPostProcessingChain(Iterator<Entry<Key,Document>> postProcessingBase) {
        String functions = postProcessingFunctions;
        if (functions != null && !functions.isEmpty()) {
            try {
                Iterator tforms = postProcessingBase;
                for (String fClassName : StringUtils.splitIterable(functions, ',', true)) {
                    if (log.isTraceEnabled()) {
                        log.trace("Configuring post-processing class: " + fClassName);
                    }
                    Class<?> fClass = Class.forName(fClassName);
                    if (Function.class.isAssignableFrom(fClass)) {
                        Function f = (Function) fClass.newInstance();
                        
                        if (f instanceof ConfiguredFunction) {
                            ((ConfiguredFunction) f).configure(options);
                        }
                        
                        tforms = Iterators.transform(tforms, f);
                    } else if (Predicate.class.isAssignableFrom(fClass)) {
                        Predicate p = (Predicate) fClass.newInstance();
                        
                        if (p instanceof ConfiguredPredicate) {
                            ((ConfiguredPredicate) p).configure(options);
                        }
                        
                        tforms = QueryIterator.statelessFilter(tforms, p);
                    } else {
                        log.error(fClass + " is not a function or predicate.");
                        throw new RuntimeException(fClass + " is not a function or predicate.");
                    }
                }
                return tforms;
            } catch (ClassNotFoundException | IllegalAccessException | InstantiationException e) {
                log.error("Could not instantiate postprocessing chain!", e);
                throw new RuntimeException("Could not instantiate postprocessing chain!", e);
            }
        }
        return postProcessingBase;
    }
    
    public boolean isTermFrequenciesRequired() {
        return termFrequenciesRequired;
    }
    
    public void setTermFrequenciesRequired(boolean termFrequenciesRequired) {
        this.termFrequenciesRequired = termFrequenciesRequired;
    }
    
    public Set<String> parseTermFrequencyFields(Map<String,String> options) {
        String val = options.get(TERM_FREQUENCY_FIELDS);
        if (val == null) {
            return Collections.emptySet();
        } else {
            return ImmutableSet.copyOf(Splitter.on(',').trimResults().split(val));
        }
    }
    
    public Set<String> getTermFrequencyFields() {
        return termFrequencyFields;
    }
    
    public void setTermFrequencyFields(Set<String> termFrequencyFields) {
        this.termFrequencyFields = termFrequencyFields;
    }
    
    public Set<String> parseContentExpansionFields(Map<String,String> options) {
        String val = options.get(CONTENT_EXPANSION_FIELDS);
        if (val == null) {
            return Collections.emptySet();
        } else {
            return ImmutableSet.copyOf(Splitter.on(',').trimResults().split(val));
        }
    }
    
    public Set<String> getContentExpansionFields() {
        return contentExpansionFields;
    }
    
    public void setContentExpansionFields(Set<String> contentExpansionFields) {
        this.contentExpansionFields = contentExpansionFields;
    }
    
    public int getMaxEvaluationPipelines() {
        return maxEvaluationPipelines;
    }
    
    public void setMaxEvaluationPipelines(int maxEvaluationPipelines) {
        this.maxEvaluationPipelines = maxEvaluationPipelines;
    }
    
    public int getMaxPipelineCachedResults() {
        return maxPipelineCachedResults;
    }
    
    public void setMaxPipelineCachedResults(int maxCachedResults) {
        this.maxPipelineCachedResults = maxCachedResults;
    }
    
    public String getStatsdHostAndPort() {
        return statsdHostAndPort;
    }
    
    public void setStatsdHostAndPort(String statsdHostAndPort) {
        this.statsdHostAndPort = statsdHostAndPort;
    }
    
    public QueryStatsDClient getStatsdClient() {
        if (statsdHostAndPort != null && queryId != null) {
            if (statsdClient == null) {
                synchronized (queryId) {
                    if (statsdClient == null) {
                        setStatsdClient(new QueryStatsDClient(queryId, getStatsdHost(statsdHostAndPort), getStatsdPort(statsdHostAndPort),
                                        getStatsdMaxQueueSize()));
                    }
                }
            }
        }
        return statsdClient;
    }
    
    private String getStatsdHost(String statsdHostAndPort) {
        int index = statsdHostAndPort.indexOf(':');
        if (index == -1) {
            return statsdHostAndPort;
        } else if (index == 0) {
            return "localhost";
        } else {
            return statsdHostAndPort.substring(0, index);
        }
    }
    
    private int getStatsdPort(String statsdHostAndPort) {
        int index = statsdHostAndPort.indexOf(':');
        if (index == -1) {
            return 8125;
        } else if (index == statsdHostAndPort.length() - 1) {
            return 8125;
        } else {
            return Integer.parseInt(statsdHostAndPort.substring(index + 1));
        }
    }
    
    public void setStatsdClient(QueryStatsDClient statsdClient) {
        this.statsdClient = statsdClient;
    }
    
    public int getStatsdMaxQueueSize() {
        return statsdMaxQueueSize;
    }
    
    public void setStatsdMaxQueueSize(int statsdMaxQueueSize) {
        this.statsdMaxQueueSize = statsdMaxQueueSize;
    }
    
    public long getYieldThresholdMs() {
        return yieldThresholdMs;
    }
    
    public void setYieldThresholdMs(long yieldThresholdMs) {
        this.yieldThresholdMs = yieldThresholdMs;
    }
    
}
