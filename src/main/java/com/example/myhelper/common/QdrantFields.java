package com.example.myhelper.common;

/**
 * Qdrant REST API JSON 字段名常量。
 * 集中管理散落在各 Service 中的硬编码 JSON key 字符串。
 */
public final class QdrantFields {

    private QdrantFields() {}

    // -- Collection 操作 --
    public static final String VECTORS = "vectors";
    public static final String SIZE = "size";
    public static final String DISTANCE = "distance";
    public static final String COSINE = "Cosine";

    // -- Point 字段 --
    public static final String POINTS = "points";
    public static final String ID = "id";
    public static final String VECTOR = "vector";
    public static final String PAYLOAD = "payload";

    // -- 过滤 --
    public static final String FILTER = "filter";
    public static final String MUST = "must";
    public static final String KEY = "key";
    public static final String MATCH = "match";
    public static final String VALUE = "value";
    public static final String RANGE = "range";
    public static final String GTE = "gte";

    // -- 查询 --
    public static final String LIMIT = "limit";
    public static final String OFFSET = "offset";
    public static final String WITH_PAYLOAD = "with_payload";
    public static final String WITH_VECTOR = "with_vector";
    public static final String SCORE_THRESHOLD = "score_threshold";
    public static final String IDS = "ids";

    // -- 响应 --
    public static final String RESULT = "result";
    public static final String SCORE = "score";
    public static final String STATUS = "status";
    public static final String NEXT_PAGE_OFFSET = "next_page_offset";

    // -- Payload 字段 --
    public static final String USER_INPUT = "userInput";
    public static final String SELECTED_TOOL_NAMES = "selectedToolNames";
    public static final String MISSING_DESCRIPTIONS = "missingDescriptions";
    public static final String TOOL_CALLS = "toolCalls";
    public static final String AI_RESPONSE = "aiResponse";
    public static final String SUCCESS_LESSON = "successLesson";
    public static final String FAILURE_LESSON = "failureLesson";
    public static final String SIGNATURE = "signature";
    public static final String UNIT_TYPE = "unitType";
    public static final String IS_GENERIC = "isGeneric";
    public static final String PARENT_IDS = "parentIds";
    public static final String SUCCESS_COUNT = "successCount";
    public static final String FAILURE_COUNT = "failureCount";
    public static final String ARCHIVED = "archived";
    public static final String TIMESTAMP = "timestamp";
    public static final String STABILITY = "stability";
    public static final String CAN_SCRIPT = "canScript";
    public static final String FAILED_STEP_INDEX = "failedStepIndex";

    // -- Category 字段 --
    public static final String NAME = "name";
    public static final String DESCRIPTION = "description";
    public static final String DESC = "desc";
    public static final String TOOLS = "tools";
    public static final String TOOL_COUNT = "toolCount";
    public static final String VERSION = "version";
}
