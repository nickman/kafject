package com.heliosapm.metrichub.speedment.tsdb.public_.tsd_metric.generated;

import com.heliosapm.metrichub.speedment.tsdb.public_.tsd_metric.TsdMetric;
import com.speedment.runtime.core.util.OptionalUtil;
import java.sql.Timestamp;
import java.util.Objects;
import java.util.Optional;
import java.util.StringJoiner;
import javax.annotation.Generated;

/**
 * The generated base implementation of the {@link
 * com.heliosapm.metrichub.speedment.tsdb.public_.tsd_metric.TsdMetric}-interface.
 * <p>
 * This file has been automatically generated by Speedment. Any changes made to
 * it will be overwritten.
 * 
 * @author Speedment
 */
@Generated("Speedment")
public abstract class GeneratedTsdMetricImpl implements TsdMetric {
    
    private String xuid;
    private int version;
    private String name;
    private Timestamp created;
    private Timestamp lastUpdate;
    private String description;
    private String displayName;
    private String notes;
    private String custom;
    
    protected GeneratedTsdMetricImpl() {
        
    }
    
    @Override
    public String getXuid() {
        return xuid;
    }
    
    @Override
    public int getVersion() {
        return version;
    }
    
    @Override
    public String getName() {
        return name;
    }
    
    @Override
    public Timestamp getCreated() {
        return created;
    }
    
    @Override
    public Timestamp getLastUpdate() {
        return lastUpdate;
    }
    
    @Override
    public Optional<String> getDescription() {
        return Optional.ofNullable(description);
    }
    
    @Override
    public Optional<String> getDisplayName() {
        return Optional.ofNullable(displayName);
    }
    
    @Override
    public Optional<String> getNotes() {
        return Optional.ofNullable(notes);
    }
    
    @Override
    public Optional<String> getCustom() {
        return Optional.ofNullable(custom);
    }
    
    @Override
    public TsdMetric setXuid(String xuid) {
        this.xuid = xuid;
        return this;
    }
    
    @Override
    public TsdMetric setVersion(int version) {
        this.version = version;
        return this;
    }
    
    @Override
    public TsdMetric setName(String name) {
        this.name = name;
        return this;
    }
    
    @Override
    public TsdMetric setCreated(Timestamp created) {
        this.created = created;
        return this;
    }
    
    @Override
    public TsdMetric setLastUpdate(Timestamp lastUpdate) {
        this.lastUpdate = lastUpdate;
        return this;
    }
    
    @Override
    public TsdMetric setDescription(String description) {
        this.description = description;
        return this;
    }
    
    @Override
    public TsdMetric setDisplayName(String displayName) {
        this.displayName = displayName;
        return this;
    }
    
    @Override
    public TsdMetric setNotes(String notes) {
        this.notes = notes;
        return this;
    }
    
    @Override
    public TsdMetric setCustom(String custom) {
        this.custom = custom;
        return this;
    }
    
    @Override
    public String toString() {
        final StringJoiner sj = new StringJoiner(", ", "{ ", " }");
        sj.add("xuid = " + Objects.toString(getXuid()));
        sj.add("version = " + Objects.toString(getVersion()));
        sj.add("name = " + Objects.toString(getName()));
        sj.add("created = " + Objects.toString(getCreated()));
        sj.add("lastUpdate = " + Objects.toString(getLastUpdate()));
        sj.add("description = " + Objects.toString(OptionalUtil.unwrap(getDescription())));
        sj.add("displayName = " + Objects.toString(OptionalUtil.unwrap(getDisplayName())));
        sj.add("notes = " + Objects.toString(OptionalUtil.unwrap(getNotes())));
        sj.add("custom = " + Objects.toString(OptionalUtil.unwrap(getCustom())));
        return "TsdMetricImpl " + sj.toString();
    }
    
    @Override
    public boolean equals(Object that) {
        if (this == that) { return true; }
        if (!(that instanceof TsdMetric)) { return false; }
        final TsdMetric thatTsdMetric = (TsdMetric)that;
        if (!Objects.equals(this.getXuid(), thatTsdMetric.getXuid())) {return false; }
        if (this.getVersion() != thatTsdMetric.getVersion()) {return false; }
        if (!Objects.equals(this.getName(), thatTsdMetric.getName())) {return false; }
        if (!Objects.equals(this.getCreated(), thatTsdMetric.getCreated())) {return false; }
        if (!Objects.equals(this.getLastUpdate(), thatTsdMetric.getLastUpdate())) {return false; }
        if (!Objects.equals(this.getDescription(), thatTsdMetric.getDescription())) {return false; }
        if (!Objects.equals(this.getDisplayName(), thatTsdMetric.getDisplayName())) {return false; }
        if (!Objects.equals(this.getNotes(), thatTsdMetric.getNotes())) {return false; }
        if (!Objects.equals(this.getCustom(), thatTsdMetric.getCustom())) {return false; }
        return true;
    }
    
    @Override
    public int hashCode() {
        int hash = 7;
        hash = 31 * hash + Objects.hashCode(getXuid());
        hash = 31 * hash + Integer.hashCode(getVersion());
        hash = 31 * hash + Objects.hashCode(getName());
        hash = 31 * hash + Objects.hashCode(getCreated());
        hash = 31 * hash + Objects.hashCode(getLastUpdate());
        hash = 31 * hash + Objects.hashCode(getDescription());
        hash = 31 * hash + Objects.hashCode(getDisplayName());
        hash = 31 * hash + Objects.hashCode(getNotes());
        hash = 31 * hash + Objects.hashCode(getCustom());
        return hash;
    }
}