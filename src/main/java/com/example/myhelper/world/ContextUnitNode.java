package com.example.myhelper.world;

import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;

@Node("ContextUnit")
public class ContextUnitNode {
    @Id private String contextId;
    private String contextRole;
    private String subjectId;
    private String predicate;
    private String operator;
    private String objectId;
    private String literalValueJson;
    private String stateId;
    private String source;
    private String origin;
    private Long observedAt;
    private Long validUntil;
    private double confidence;
    private double requiredConfidence;
    private long maximumAgeMillis;
    private String refreshUnitId;
    private String evidenceIdsJson;
    private boolean active;
    private Long updatedAt;

    public ContextUnitNode() {}
    public String getContextId() { return contextId; }
    public void setContextId(String contextId) { this.contextId = contextId; }
    public String getContextRole() { return contextRole; }
    public void setContextRole(String contextRole) { this.contextRole = contextRole; }
    public String getSubjectId() { return subjectId; }
    public void setSubjectId(String subjectId) { this.subjectId = subjectId; }
    public String getPredicate() { return predicate; }
    public void setPredicate(String predicate) { this.predicate = predicate; }
    public String getOperator() { return operator; }
    public void setOperator(String operator) { this.operator = operator; }
    public String getObjectId() { return objectId; }
    public void setObjectId(String objectId) { this.objectId = objectId; }
    public String getLiteralValueJson() { return literalValueJson; }
    public void setLiteralValueJson(String literalValueJson) { this.literalValueJson = literalValueJson; }
    public String getStateId() { return stateId; }
    public void setStateId(String stateId) { this.stateId = stateId; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getOrigin() { return origin; }
    public void setOrigin(String origin) { this.origin = origin; }
    public Long getObservedAt() { return observedAt; }
    public void setObservedAt(Long observedAt) { this.observedAt = observedAt; }
    public Long getValidUntil() { return validUntil; }
    public void setValidUntil(Long validUntil) { this.validUntil = validUntil; }
    public double getConfidence() { return confidence; }
    public void setConfidence(double confidence) { this.confidence = confidence; }
    public double getRequiredConfidence() { return requiredConfidence; }
    public void setRequiredConfidence(double requiredConfidence) { this.requiredConfidence = requiredConfidence; }
    public long getMaximumAgeMillis() { return maximumAgeMillis; }
    public void setMaximumAgeMillis(long maximumAgeMillis) { this.maximumAgeMillis = maximumAgeMillis; }
    public String getRefreshUnitId() { return refreshUnitId; }
    public void setRefreshUnitId(String refreshUnitId) { this.refreshUnitId = refreshUnitId; }
    public String getEvidenceIdsJson() { return evidenceIdsJson; }
    public void setEvidenceIdsJson(String evidenceIdsJson) { this.evidenceIdsJson = evidenceIdsJson; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public Long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Long updatedAt) { this.updatedAt = updatedAt; }
}
