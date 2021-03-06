/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2018-2019 The Feast Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package feast.core.model;

import com.google.protobuf.Duration;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Timestamp;
import feast.core.FeatureSetProto;
import feast.core.FeatureSetProto.*;
import feast.core.util.TypeConversion;
import feast.types.ValueProto.ValueType.Enum;
import java.util.*;
import javax.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.apache.commons.lang3.builder.HashCodeBuilder;
import org.tensorflow.metadata.v0.*;

@Getter
@Setter
@javax.persistence.Entity
@Table(
    name = "feature_sets",
    uniqueConstraints = @UniqueConstraint(columnNames = {"name", "version", "project_name"}))
public class FeatureSet extends AbstractTimestampEntity implements Comparable<FeatureSet> {

  // Id of the featureSet, defined as project/feature_set_name:feature_set_version
  @Id @GeneratedValue private long id;

  // Name of the featureSet
  @Column(name = "name", nullable = false)
  private String name;

  // Version of the featureSet
  @Column(name = "version")
  private int version;

  // Project that this featureSet belongs to
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "project_name")
  private Project project;

  // Max allowed staleness for features in this featureSet.
  @Column(name = "max_age")
  private long maxAgeSeconds;

  // Entity fields inside this feature set
  @OneToMany(
      mappedBy = "featureSet",
      cascade = CascadeType.ALL,
      fetch = FetchType.EAGER,
      orphanRemoval = true)
  private Set<Entity> entities;

  // Feature fields inside this feature set
  @OneToMany(
      mappedBy = "featureSet",
      cascade = CascadeType.ALL,
      fetch = FetchType.EAGER,
      orphanRemoval = true)
  private Set<Feature> features;

  // Source on which feature rows can be found
  @ManyToOne(cascade = CascadeType.ALL, fetch = FetchType.EAGER)
  @JoinColumn(name = "source")
  private Source source;

  // Status of the feature set
  @Column(name = "status")
  private String status;

  // User defined metadata
  @Column(name = "labels", columnDefinition = "text")
  private String labels;

  public FeatureSet() {
    super();
  }

  public FeatureSet(
      String name,
      String project,
      int version,
      long maxAgeSeconds,
      List<Entity> entities,
      List<Feature> features,
      Source source,
      Map<String, String> labels,
      FeatureSetStatus status) {
    this.maxAgeSeconds = maxAgeSeconds;
    this.source = source;
    this.status = status.toString();
    this.entities = new HashSet<>();
    this.features = new HashSet<>();
    this.name = name;
    this.project = new Project(project);
    this.version = version;
    this.labels = TypeConversion.convertMapToJsonString(labels);
    addEntities(entities);
    addFeatures(features);
  }

  public void setVersion(int version) {
    this.version = version;
  }

  public void setName(String name) {
    this.name = name;
  }

  private String getProjectName() {
    if (getProject() != null) {
      return getProject().getName();
    } else {
      return "";
    }
  }

  public void setProject(Project project) {
    this.project = project;
  }

  public static FeatureSet fromProto(FeatureSetProto.FeatureSet featureSetProto) {
    FeatureSetSpec featureSetSpec = featureSetProto.getSpec();
    Source source = Source.fromProto(featureSetSpec.getSource());

    List<Feature> featureSpecs = new ArrayList<>();
    for (FeatureSpec featureSpec : featureSetSpec.getFeaturesList()) {
      featureSpecs.add(Feature.fromProto(featureSpec));
    }

    List<Entity> entitySpecs = new ArrayList<>();
    for (EntitySpec entitySpec : featureSetSpec.getEntitiesList()) {
      entitySpecs.add(Entity.fromProto(entitySpec));
    }

    return new FeatureSet(
        featureSetProto.getSpec().getName(),
        featureSetProto.getSpec().getProject(),
        featureSetProto.getSpec().getVersion(),
        featureSetSpec.getMaxAge().getSeconds(),
        entitySpecs,
        featureSpecs,
        source,
        featureSetProto.getSpec().getLabelsMap(),
        featureSetProto.getMeta().getStatus());
  }

  public void addEntities(List<Entity> entities) {
    for (Entity entity : entities) {
      addEntity(entity);
    }
  }

  public void addEntity(Entity entity) {
    entity.setFeatureSet(this);
    entities.add(entity);
  }

  public void addFeatures(List<Feature> features) {
    for (Feature feature : features) {
      addFeature(feature);
    }
  }

  public void addFeature(Feature feature) {
    feature.setFeatureSet(this);
    features.add(feature);
  }

  public FeatureSetProto.FeatureSet toProto() throws InvalidProtocolBufferException {
    List<EntitySpec> entitySpecs = new ArrayList<>();
    for (Entity entityField : entities) {
      EntitySpec.Builder entitySpecBuilder = EntitySpec.newBuilder();
      setEntitySpecFields(entitySpecBuilder, entityField);
      entitySpecs.add(entitySpecBuilder.build());
    }

    List<FeatureSpec> featureSpecs = new ArrayList<>();
    for (Feature featureField : features) {
      FeatureSpec.Builder featureSpecBuilder = FeatureSpec.newBuilder();
      setFeatureSpecFields(featureSpecBuilder, featureField);
      featureSpecs.add(featureSpecBuilder.build());
    }

    FeatureSetMeta.Builder meta =
        FeatureSetMeta.newBuilder()
            .setCreatedTimestamp(
                Timestamp.newBuilder().setSeconds(super.getCreated().getTime() / 1000L))
            .setStatus(FeatureSetStatus.valueOf(status));

    FeatureSetSpec.Builder spec =
        FeatureSetSpec.newBuilder()
            .setName(getName())
            .setVersion(getVersion())
            .setProject(project.getName())
            .setMaxAge(Duration.newBuilder().setSeconds(maxAgeSeconds))
            .addAllEntities(entitySpecs)
            .addAllFeatures(featureSpecs)
            .putAllLabels(TypeConversion.convertJsonStringToMap(labels))
            .setSource(source.toProto());

    return FeatureSetProto.FeatureSet.newBuilder().setMeta(meta).setSpec(spec).build();
  }

  private void setEntitySpecFields(EntitySpec.Builder entitySpecBuilder, Entity entityField) {
    entitySpecBuilder
        .setName(entityField.getName())
        .setValueType(Enum.valueOf(entityField.getType()));
  }

  private void setFeatureSpecFields(FeatureSpec.Builder featureSpecBuilder, Feature featureField)
      throws InvalidProtocolBufferException {
    featureSpecBuilder
        .setName(featureField.getName())
        .setValueType(Enum.valueOf(featureField.getType()));

    if (featureField.getPresence() != null) {
      featureSpecBuilder.setPresence(FeaturePresence.parseFrom(featureField.getPresence()));
    } else if (featureField.getGroupPresence() != null) {
      featureSpecBuilder.setGroupPresence(
          FeaturePresenceWithinGroup.parseFrom(featureField.getGroupPresence()));
    }

    if (featureField.getShape() != null) {
      featureSpecBuilder.setShape(FixedShape.parseFrom(featureField.getShape()));
    } else if (featureField.getValueCount() != null) {
      featureSpecBuilder.setValueCount(ValueCount.parseFrom(featureField.getValueCount()));
    }

    if (featureField.getDomain() != null) {
      featureSpecBuilder.setDomain(featureField.getDomain());
    } else if (featureField.getIntDomain() != null) {
      featureSpecBuilder.setIntDomain(IntDomain.parseFrom(featureField.getIntDomain()));
    } else if (featureField.getFloatDomain() != null) {
      featureSpecBuilder.setFloatDomain(FloatDomain.parseFrom(featureField.getFloatDomain()));
    } else if (featureField.getStringDomain() != null) {
      featureSpecBuilder.setStringDomain(StringDomain.parseFrom(featureField.getStringDomain()));
    } else if (featureField.getBoolDomain() != null) {
      featureSpecBuilder.setBoolDomain(BoolDomain.parseFrom(featureField.getBoolDomain()));
    } else if (featureField.getStructDomain() != null) {
      featureSpecBuilder.setStructDomain(StructDomain.parseFrom(featureField.getStructDomain()));
    } else if (featureField.getNaturalLanguageDomain() != null) {
      featureSpecBuilder.setNaturalLanguageDomain(
          NaturalLanguageDomain.parseFrom(featureField.getNaturalLanguageDomain()));
    } else if (featureField.getImageDomain() != null) {
      featureSpecBuilder.setImageDomain(ImageDomain.parseFrom(featureField.getImageDomain()));
    } else if (featureField.getMidDomain() != null) {
      featureSpecBuilder.setMidDomain(MIDDomain.parseFrom(featureField.getMidDomain()));
    } else if (featureField.getUrlDomain() != null) {
      featureSpecBuilder.setUrlDomain(URLDomain.parseFrom(featureField.getUrlDomain()));
    } else if (featureField.getTimeDomain() != null) {
      featureSpecBuilder.setTimeDomain(TimeDomain.parseFrom(featureField.getTimeDomain()));
    } else if (featureField.getTimeOfDayDomain() != null) {
      featureSpecBuilder.setTimeOfDayDomain(
          TimeOfDayDomain.parseFrom(featureField.getTimeOfDayDomain()));
    }

    if (featureField.getLabels() != null) {
      featureSpecBuilder.putAllLabels(featureField.getLabels());
    }
  }

  /**
   * Checks if the given featureSet's schema and source has is different from this one.
   *
   * @param other FeatureSet to compare to
   * @return boolean denoting if the source or schema have changed.
   */
  public boolean equalTo(FeatureSet other) {
    if (!getName().equals(other.getName())) {
      return false;
    }

    if (!getLabels().equals(other.getLabels())) {
      return false;
    }

    if (!project.getName().equals(other.project.getName())) {
      return false;
    }

    if (!source.equalTo(other.getSource())) {
      return false;
    }

    if (maxAgeSeconds != other.maxAgeSeconds) {
      return false;
    }

    // Create a map of all fields in this feature set
    Map<String, Entity> entitiesMap = new HashMap<>();
    Map<String, Feature> featuresMap = new HashMap<>();

    for (Entity e : entities) {
      entitiesMap.putIfAbsent(e.getName(), e);
    }

    for (Feature f : features) {
      featuresMap.putIfAbsent(f.getName(), f);
    }

    // Ensure map size is consistent with existing fields
    if (entitiesMap.size() != other.getEntities().size()) {
      return false;
    }
    if (featuresMap.size() != other.getFeatures().size()) {
      return false;
    }

    // Ensure the other entities and features exist in the field map
    for (Entity e : other.getEntities()) {
      if (!entitiesMap.containsKey(e.getName())) {
        return false;
      }
      if (!e.equals(entitiesMap.get(e.getName()))) {
        return false;
      }
    }

    for (Feature f : other.getFeatures()) {
      if (!featuresMap.containsKey(f.getName())) {
        return false;
      }
      if (!f.equals(featuresMap.get(f.getName()))) {
        return false;
      }
    }

    return true;
  }

  @Override
  public int hashCode() {
    HashCodeBuilder hcb = new HashCodeBuilder();
    hcb.append(project.getName());
    hcb.append(getName());
    hcb.append(getVersion());
    return hcb.toHashCode();
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    }
    if (!(obj instanceof FeatureSet)) {
      return false;
    }
    return this.equalTo(((FeatureSet) obj));
  }

  @Override
  public int compareTo(FeatureSet o) {
    return Integer.compare(getVersion(), o.getVersion());
  }
}
