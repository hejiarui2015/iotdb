/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.iotdb.db.metadata.path;

import java.io.IOException;
import java.io.Serializable;
import java.util.List;
import java.util.Set;
import org.apache.iotdb.db.conf.IoTDBConstant;
import org.apache.iotdb.db.engine.querycontext.QueryDataSource;
import org.apache.iotdb.db.engine.querycontext.ReadOnlyMemChunk;
import org.apache.iotdb.db.engine.storagegroup.TsFileResource;
import org.apache.iotdb.db.exception.metadata.IllegalPathException;
import org.apache.iotdb.db.query.context.QueryContext;
import org.apache.iotdb.db.query.filter.TsFileFilter;
import org.apache.iotdb.db.query.reader.series.SeriesReader;
import org.apache.iotdb.db.utils.TestOnly;
import org.apache.iotdb.tsfile.file.metadata.IChunkMetadata;
import org.apache.iotdb.tsfile.file.metadata.TimeseriesMetadata;
import org.apache.iotdb.tsfile.file.metadata.enums.TSDataType;
import org.apache.iotdb.tsfile.file.metadata.statistics.Statistics;
import org.apache.iotdb.tsfile.read.filter.basic.Filter;
import org.apache.iotdb.tsfile.write.schema.IMeasurementSchema;

public class MeasurementPath extends PartialPath {

  private IMeasurementSchema measurementSchema;

  private boolean isUnderAlignedEntity = false;

  // alias of measurement, null pointer cannot be serialized in thrift so empty string is instead
  private String measurementAlias = "";

  public MeasurementPath() {}

  public MeasurementPath(PartialPath measurementPath) {
    super(measurementPath.getNodes());
  }

  public MeasurementPath(String device, String measurement, IMeasurementSchema measurementSchema)
      throws IllegalPathException {
    super(device, measurement);
    this.measurementSchema = measurementSchema;
  }

  public IMeasurementSchema getMeasurementSchema() {
    return measurementSchema;
  }

  public void setMeasurementSchema(IMeasurementSchema measurementSchema) {
    this.measurementSchema = measurementSchema;
  }

  public String getMeasurementAlias() {
    return measurementAlias;
  }

  public void setMeasurementAlias(String measurementAlias) {
    if (measurementAlias != null) {
      this.measurementAlias = measurementAlias;
    }
  }

  public boolean isMeasurementAliasExists() {
    return measurementAlias != null && !measurementAlias.isEmpty();
  }

  @Override
  public String getFullPathWithAlias() {
    return getDevice() + IoTDBConstant.PATH_SEPARATOR + measurementAlias;
  }

  public boolean isUnderAlignedEntity() {
    return isUnderAlignedEntity;
  }

  public void setUnderAlignedEntity(boolean underAlignedEntity) {
    isUnderAlignedEntity = underAlignedEntity;
  }

  public PartialPath copy() {
    MeasurementPath result = new MeasurementPath();
    result.nodes = nodes;
    result.fullPath = fullPath;
    result.device = device;
    result.measurementAlias = measurementAlias;
    return result;
  }

  public SeriesReader createSeriesReader(
      Set<String> allSensors,
      TSDataType dataType,
      QueryContext context,
      QueryDataSource dataSource,
      Filter timeFilter,
      Filter valueFilter,
      TsFileFilter fileFilter,
      boolean ascending) {
    return new SeriesReader(
        this,
        allSensors,
        dataType,
        context,
        dataSource,
        timeFilter,
        valueFilter,
        fileFilter,
        ascending);
  }

  @TestOnly
  public SeriesReader createSeriesReader(
      Set<String> allSensors,
      TSDataType dataType,
      QueryContext context,
      List<TsFileResource> seqFileResource,
      List<TsFileResource> unseqFileResource,
      Filter timeFilter,
      Filter valueFilter,
      boolean ascending) {
    return new SeriesReader(
        this,
        allSensors,
        dataType,
        context,
        seqFileResource,
        unseqFileResource,
        timeFilter,
        valueFilter,
        ascending);
  }

  @Override
  public TsFileResource createTsFileResource(List<ReadOnlyMemChunk> readOnlyMemChunk,
      List<IChunkMetadata> chunkMetadataList, TsFileResource originTsFileResource)
      throws IOException {
    TsFileResource tsFileResource = new TsFileResource(readOnlyMemChunk, chunkMetadataList, originTsFileResource);
    tsFileResource.setTimeSeriesMetadata(generateTimeSeriesMetadata(readOnlyMemChunk, chunkMetadataList));
    return tsFileResource;
  }

  /**
   * Because the unclosed tsfile don't have TimeSeriesMetadata and memtables in the memory don't
   * have chunkMetadata, but query will use these, so we need to generate it for them.
   */
  private TimeseriesMetadata generateTimeSeriesMetadata(List<ReadOnlyMemChunk> readOnlyMemChunk,
      List<IChunkMetadata> chunkMetadataList) throws IOException {
    TimeseriesMetadata timeSeriesMetadata = new TimeseriesMetadata();
    timeSeriesMetadata.setMeasurementId(measurementSchema.getMeasurementId());
    timeSeriesMetadata.setTSDataType(measurementSchema.getType());
    timeSeriesMetadata.setOffsetOfChunkMetaDataList(-1);
    timeSeriesMetadata.setDataSizeOfChunkMetaDataList(-1);

    Statistics<? extends Serializable> seriesStatistics =
        Statistics.getStatsByType(timeSeriesMetadata.getTSDataType());
    // flush chunkMetadataList one by one
    for (IChunkMetadata chunkMetadata : chunkMetadataList) {
      seriesStatistics.mergeStatistics(chunkMetadata.getStatistics());
    }

    for (ReadOnlyMemChunk memChunk : readOnlyMemChunk) {
      if (!memChunk.isEmpty()) {
        seriesStatistics.mergeStatistics(memChunk.getChunkMetaData().getStatistics());
      }
    }
    timeSeriesMetadata.setStatistics(seriesStatistics);
    return timeSeriesMetadata;
  }
}