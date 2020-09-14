/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.iotdb.cluster.log.snapshot;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import org.apache.iotdb.cluster.RemoteTsFileResource;
import org.apache.iotdb.cluster.client.async.AsyncDataClient;
import org.apache.iotdb.cluster.client.sync.SyncClientAdaptor;
import org.apache.iotdb.cluster.client.sync.SyncDataClient;
import org.apache.iotdb.cluster.config.ClusterDescriptor;
import org.apache.iotdb.cluster.exception.CheckConsistencyException;
import org.apache.iotdb.cluster.exception.PullFileException;
import org.apache.iotdb.cluster.exception.SnapshotInstallationException;
import org.apache.iotdb.cluster.log.Snapshot;
import org.apache.iotdb.cluster.partition.slot.SlotManager;
import org.apache.iotdb.cluster.partition.slot.SlotManager.SlotStatus;
import org.apache.iotdb.cluster.rpc.thrift.Node;
import org.apache.iotdb.cluster.server.member.DataGroupMember;
import org.apache.iotdb.cluster.server.member.RaftMember;
import org.apache.iotdb.cluster.utils.ClientUtils;
import org.apache.iotdb.db.conf.IoTDBDescriptor;
import org.apache.iotdb.db.engine.StorageEngine;
import org.apache.iotdb.db.engine.modification.ModificationFile;
import org.apache.iotdb.db.engine.storagegroup.TsFileResource;
import org.apache.iotdb.db.exception.LoadFileException;
import org.apache.iotdb.db.exception.StorageEngineException;
import org.apache.iotdb.db.exception.metadata.IllegalPathException;
import org.apache.iotdb.db.metadata.PartialPath;
import org.apache.iotdb.db.utils.FilePathUtils;
import org.apache.iotdb.db.utils.SchemaUtils;
import org.apache.iotdb.tsfile.write.schema.TimeseriesSchema;
import org.apache.thrift.TException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * FileSnapshot records the data files in a slot and their md5 (or other verification).
 * When the snapshot is used to perform a catch-up, the receiver should:
 * 1. create a remote snapshot indicating that the slot is being pulled from the remote
 * 2. traverse the file list, for each file:
 *  2.1 if the file exists locally and the md5 is correct, skip it.
 *  2.2 otherwise pull the file from the remote.
 * 3. replace the remote snapshot with a FileSnapshot indicating that the slot of this node is
 * synchronized with the remote one.
 */
public class FileSnapshot extends Snapshot implements TimeseriesSchemaSnapshot {

  private Collection<TimeseriesSchema> timeseriesSchemas;
  private List<RemoteTsFileResource> dataFiles;

  public FileSnapshot() {
    dataFiles = new ArrayList<>();
    timeseriesSchemas = new HashSet<>();
  }

  public void addFile(TsFileResource resource, Node header) throws IOException {
    dataFiles.add(new RemoteTsFileResource(resource, header));
  }

  @Override
  public ByteBuffer serialize() {
    ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
    DataOutputStream dataOutputStream = new DataOutputStream(byteArrayOutputStream);

    try {
      dataOutputStream.writeInt(timeseriesSchemas.size());
      for (TimeseriesSchema measurementSchema : timeseriesSchemas) {
        measurementSchema.serializeTo(dataOutputStream);
      }
      dataOutputStream.writeInt(dataFiles.size());
      for (RemoteTsFileResource dataFile : dataFiles) {
        dataFile.serialize(dataOutputStream);
      }
    } catch (IOException ignored) {
      // unreachable
    }

    return ByteBuffer.wrap(byteArrayOutputStream.toByteArray());
  }

  @Override
  public void deserialize(ByteBuffer buffer) {
    int timeseriesNum = buffer.getInt();
    for (int i = 0; i < timeseriesNum; i++) {
      timeseriesSchemas.add(TimeseriesSchema.deserializeFrom(buffer));
    }
    int fileNum = buffer.getInt();
    for (int i = 0; i < fileNum; i++) {
      RemoteTsFileResource resource = new RemoteTsFileResource();
      resource.deserialize(buffer);
      dataFiles.add(resource);
    }
  }

  public List<RemoteTsFileResource> getDataFiles() {
    return dataFiles;
  }

  @Override
  public Collection<TimeseriesSchema> getTimeseriesSchemas() {
    return timeseriesSchemas;
  }

  @Override
  public void setTimeseriesSchemas(
      Collection<TimeseriesSchema> timeseriesSchemas) {
    this.timeseriesSchemas = timeseriesSchemas;
  }

  @Override
  public String toString() {
    return "FileSnapshot{" +
        "timeseriesSchemas=" + timeseriesSchemas.size() +
        ", dataFiles=" + dataFiles.size() +
        ", lastLogIndex=" + lastLogIndex +
        ", lastLogTerm=" + lastLogTerm +
        '}';
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    FileSnapshot that = (FileSnapshot) o;
    return Objects.equals(timeseriesSchemas, that.timeseriesSchemas) &&
        Objects.equals(dataFiles, that.dataFiles);
  }

  @Override
  public int hashCode() {
    return Objects.hash(timeseriesSchemas, dataFiles);
  }

  @Override
  public SnapshotInstaller getDefaultInstaller(RaftMember member) {
    return new Installer((DataGroupMember) member);
  }

  public static class Installer implements SnapshotInstaller<FileSnapshot> {

    /**
     * When a DataGroupMember pulls data from another node, the data files will be firstly stored in
     * the "REMOTE_FILE_TEMP_DIR", and then load file functionality of IoTDB will be used to load the
     * files into the IoTDB instance.
     */
    private static final String REMOTE_FILE_TEMP_DIR =
        IoTDBDescriptor.getInstance().getConfig().getBaseDir() + File.separator + "remote";

    private static final Logger logger = LoggerFactory.getLogger(Installer.class);
    private DataGroupMember dataGroupMember;
    private SlotManager slotManager;
    private String name;

    Installer(DataGroupMember dataGroupMember) {
      this.dataGroupMember = dataGroupMember;
      this.slotManager = dataGroupMember.getSlotManager();
      this.name = dataGroupMember.getName();
    }

    @Override
    public void install(FileSnapshot snapshot, int slot) throws SnapshotInstallationException {
      try {
        installFileSnapshotSchema(snapshot);
        installFileSnapshotVersions(snapshot, slot);
        installFileSnapshotFiles(snapshot, slot);
      } catch (PullFileException e) {
        throw new SnapshotInstallationException(e);
      }
    }

    @Override
    public void install(Map<Integer, FileSnapshot> snapshotMap)
        throws SnapshotInstallationException {
      installSnapshot(snapshotMap);
    }

    private void installSnapshot(Map<Integer, FileSnapshot> snapshotMap)
        throws SnapshotInstallationException {
      // ensure StorageGroups are synchronized
      try {
        dataGroupMember.getMetaGroupMember().syncLeaderWithConsistencyCheck();
      } catch (CheckConsistencyException e) {
        throw new SnapshotInstallationException(e);
      }

      for (FileSnapshot value : snapshotMap.values()) {
        installFileSnapshotSchema(value);
      }

      for (Entry<Integer, FileSnapshot> integerSnapshotEntry : snapshotMap.entrySet()) {
        Integer slot = integerSnapshotEntry.getKey();
        FileSnapshot snapshot = integerSnapshotEntry.getValue();
        installFileSnapshotVersions(snapshot, slot);
      }

      for (Entry<Integer, FileSnapshot> integerSnapshotEntry : snapshotMap.entrySet()) {
        Integer slot = integerSnapshotEntry.getKey();
        FileSnapshot snapshot = integerSnapshotEntry.getValue();
        try {
          installFileSnapshotFiles(snapshot, slot);
        } catch (PullFileException e) {
          throw new SnapshotInstallationException(e);
        }
      }
    }

    private void installFileSnapshotSchema(FileSnapshot snapshot) {
      // load metadata in the snapshot
      for (TimeseriesSchema schema : snapshot.getTimeseriesSchemas()) {
        // notice: the measurement in the schema is the full path here
        SchemaUtils.registerTimeseries(schema);
      }
    }

    private void installFileSnapshotVersions(FileSnapshot snapshot, int slot)
        throws SnapshotInstallationException {
      // load data in the snapshot
      List<RemoteTsFileResource> remoteTsFileResources = snapshot.getDataFiles();
      // set partition versions
      for (RemoteTsFileResource remoteTsFileResource : remoteTsFileResources) {
        String[] pathSegments = FilePathUtils.splitTsFilePath(remoteTsFileResource);
        int segSize = pathSegments.length;
        String storageGroupName = pathSegments[segSize - 3];
        try {
          StorageEngine.getInstance().setPartitionVersionToMax(new PartialPath(storageGroupName),
              remoteTsFileResource.getTimePartition(), remoteTsFileResource.getMaxVersion());
        } catch (StorageEngineException | IllegalPathException e) {
          throw new SnapshotInstallationException(e);
        }
      }
      SlotStatus status = slotManager.getStatus(slot);
      if (status == SlotStatus.PULLING) {
        // as the partition versions are set, writes can proceed without generating incorrect
        // versions
        slotManager.setToPullingWritable(slot);
        logger.debug("{}: slot {} is now pulling writable", name, slot);
      }
    }

    private void installFileSnapshotFiles(FileSnapshot snapshot, int slot)
        throws PullFileException {
      List<RemoteTsFileResource> remoteTsFileResources = snapshot.getDataFiles();
      // pull file
      for (RemoteTsFileResource resource : remoteTsFileResources) {
        try {
          if (!isFileAlreadyPulled(resource)) {
            loadRemoteFile(resource);
          }
        } catch (IllegalPathException e) {
          throw new PullFileException(resource.getTsFilePath(), resource.getSource(), e);
        }
      }
      // all files are loaded, the slot can be queried without accessing the previous holder
      slotManager.setToNull(slot);
      logger.info("{}: slot {} is ready", name, slot);
    }

    /**
     * Check if the file "resource" is a duplication of some local files. As all data file close is
     * controlled by the data group leader, the files with the same version should contain identical
     * data if without merge. Even with merge, the files that the merged file is from are recorded so
     * we can still find out if the data of a file is already replicated in this member.
     *
     * @param resource
     * @return
     */
    private boolean isFileAlreadyPulled(RemoteTsFileResource resource) throws IllegalPathException {
      String[] pathSegments = FilePathUtils.splitTsFilePath(resource);
      int segSize = pathSegments.length;
      // <storageGroupName>/<partitionNum>/<fileName>
      String storageGroupName = pathSegments[segSize - 3];
      long partitionNumber = Long.parseLong(pathSegments[segSize - 2]);
      return StorageEngine.getInstance()
          .isFileAlreadyExist(resource, new PartialPath(storageGroupName), partitionNumber);
    }

    /**
     * Load a remote file from the header of the data group that the file is in. As different IoTDB
     * instances will name the file with the same version differently, we can only pull the file from
     * the header currently.
     *
     * @param resource
     */
    private void loadRemoteFile(RemoteTsFileResource resource) throws PullFileException {
      Node sourceNode = resource.getSource();
      // pull the file to a temporary directory
      File tempFile;
      try {
        tempFile = pullRemoteFile(resource, sourceNode);
      } catch (IOException e) {
        throw new PullFileException(resource.toString(), sourceNode, e);
      }
      if (tempFile != null) {
        resource.setFile(tempFile);
        try {
          // save the resource and load the file into IoTDB
          resource.serialize();
          loadRemoteResource(resource);
          logger.info("{}: Remote file {} is successfully loaded", name, resource);
          return;
        } catch (IOException e) {
          logger.error("{}: Cannot serialize {}", name, resource, e);
        } catch (IllegalPathException e) {
          logger.error("Illegal path when loading file {}", resource, e);
        }
      }
      logger.error("{}: Cannot load remote file {} from node {}", name, resource, sourceNode);
      throw new PullFileException(resource.toString(), sourceNode);
    }

    /**
     * When a file is successfully pulled to the local storage, load it into IoTDB with the resource
     * and remove the files that is a subset of the new file. Also change the modification file if the
     * new file is with one.
     *
     * @param resource
     */
    private void loadRemoteResource(RemoteTsFileResource resource) throws IllegalPathException {
      // the new file is stored at:
      // remote/<nodeIdentifier>/<storageGroupName>/<partitionNum>/<fileName>
      String[] pathSegments = FilePathUtils.splitTsFilePath(resource);
      int segSize = pathSegments.length;
      PartialPath storageGroupName = new PartialPath(pathSegments[segSize - 3]);
      File remoteModFile =
          new File(resource.getTsFile().getAbsoluteFile() + ModificationFile.FILE_SUFFIX);
      try {
        StorageEngine.getInstance().getProcessor(storageGroupName).loadNewTsFile(resource);
        StorageEngine.getInstance().getProcessor(storageGroupName)
            .removeFullyOverlapFiles(resource);
      } catch (StorageEngineException | LoadFileException e) {
        logger.error("{}: Cannot load remote file {} into storage group", name, resource, e);
        return;
      }
      if (remoteModFile.exists()) {
        // when successfully loaded, the filepath of the resource will be changed to the IoTDB data
        // dir, so we can add a suffix to find the old modification file.
        File localModFile =
            new File(resource.getTsFile().getAbsoluteFile() + ModificationFile.FILE_SUFFIX);
        try {
          Files.delete(localModFile.toPath());
        } catch (IOException e) {
          logger.warn("Cannot delete localModFile {}", localModFile, e);
        }
        if (!remoteModFile.renameTo(localModFile)) {
          logger.warn("Cannot rename remoteModFile {}", remoteModFile);
        }
      }
      resource.setRemote(false);
    }

    /**
     * Download the remote file of "resource" from "node" to a local temporary directory. If the
     * resource has modification file, also download it.
     *
     * @param resource the TsFile to be downloaded
     * @param node     where to download the file
     * @return the downloaded file or null if the file cannot be downloaded or its MD5 is not right
     * @throws IOException
     */
    private File pullRemoteFile(RemoteTsFileResource resource, Node node) throws IOException {
      logger.debug("{}: pulling remote file {} from {}", name, resource, node);

      String[] pathSegments = FilePathUtils.splitTsFilePath(resource);
      int segSize = pathSegments.length;
      // the new file is stored at:
      // remote/<nodeIdentifier>/<storageGroupName>/<partitionNum>/<fileName>
      // the file in the snapshot is a hardlink, remove the hardlink suffix
      String tempFileName = pathSegments[segSize - 1].substring(0,
          pathSegments[segSize - 1].lastIndexOf('.'));
      String tempFilePath =
          node.getNodeIdentifier() + File.separator + pathSegments[segSize - 3] +
              File.separator + pathSegments[segSize - 2] + File.separator + tempFileName;
      File tempFile = new File(REMOTE_FILE_TEMP_DIR, tempFilePath);
      tempFile.getParentFile().mkdirs();
      File tempModFile = new File(REMOTE_FILE_TEMP_DIR,
          tempFilePath + ModificationFile.FILE_SUFFIX);
      if (pullRemoteFile(resource.getTsFile().getAbsolutePath(), node, tempFile)) {
        // TODO-Cluster#353: implement file examination, may be replaced with other algorithm
        if (resource.isWithModification()) {
          pullRemoteFile(resource.getModFile().getFilePath(), node, tempModFile);
        }
        return tempFile;
      }
      return null;
    }

    /**
     * Download the file "remotePath" from "node" and store it to "dest" using up to 64KB chunks. If
     * the network is bad, this method will retry upto 5 times before returning a failure.
     *
     * @param remotePath the file to be downloaded
     * @param node       where to download the file
     * @param dest       where to store the file
     * @return true if the file is successfully downloaded, false otherwise
     * @throws IOException
     */
    private boolean pullRemoteFile(String remotePath, Node node, File dest) throws IOException {
      int pullFileRetry = 5;
      for (int i = 0; i < pullFileRetry; i++) {
        try (BufferedOutputStream bufferedOutputStream =
            new BufferedOutputStream(new FileOutputStream(dest))) {
          if (ClusterDescriptor.getInstance().getConfig().isUseAsyncServer()) {
            downloadFileAsync(node, remotePath, bufferedOutputStream);
          } else {
            downloadFileSync(node, remotePath, bufferedOutputStream);
          }

          if (logger.isInfoEnabled()) {
            logger.info("{}: remote file {} is pulled at {}, length: {}", name, remotePath, dest,
                dest.length());
          }
          return true;
        } catch (TException e) {
          logger.warn("{}: Cannot pull file {} from {}, wait 5s to retry", name, remotePath, node,
              e);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          logger.warn("{}: Pulling file {} from {} interrupted", name, remotePath, node, e);
          return false;
        }

        try {
          Files.delete(dest.toPath());
          Thread.sleep(5000);
        } catch (IOException e) {
          logger.warn("Cannot delete file when pulling {} from {} failed", remotePath, node);
        } catch (InterruptedException ex) {
          Thread.currentThread().interrupt();
          logger.warn("{}: Pulling file {} from {} interrupted", name, remotePath, node, ex);
          return false;
        }
        // next try
      }
      return false;
    }

    private void downloadFileAsync(Node node, String remotePath, OutputStream dest)
        throws IOException, TException, InterruptedException {
      int offset = 0;
      // TODO-Cluster: use elaborate downloading techniques
      int fetchSize = 64 * 1024;

      while (true) {
        AsyncDataClient client = (AsyncDataClient) dataGroupMember.getAsyncClient(node);
        if (client == null) {
          throw new IOException("No available client for " + node.toString());
        }
        ByteBuffer buffer = SyncClientAdaptor.readFile(client, remotePath, offset, fetchSize);
        int len = writeBuffer(buffer, dest);
        if (len == 0) {
          break;
        }
        offset += len;
      }
      dest.flush();
    }

    private int writeBuffer(ByteBuffer buffer, OutputStream dest) throws IOException {
      if (buffer == null || buffer.limit() - buffer.position() == 0) {
        return 0;
      }

      // notice: the buffer returned by thrift is a slice of a larger buffer which contains
      // the whole response, so buffer.position() is not 0 initially and buffer.limit() is
      // not the size of the downloaded chunk
      dest.write(buffer.array(), buffer.position() + buffer.arrayOffset(),
          buffer.limit() - buffer.position());
      return buffer.limit() - buffer.position();
    }

    private void downloadFileSync(Node node, String remotePath, OutputStream dest)
        throws IOException, TException {
      SyncDataClient client = (SyncDataClient) dataGroupMember.getSyncClient(node);
      if (client == null) {
        throw new IOException("No available client for " + node.toString());
      }

      int offset = 0;
      // TODO-Cluster: use elaborate downloading techniques
      int fetchSize = 64 * 1024;

      try {
        while (true) {
          ByteBuffer buffer = client.readFile(remotePath, offset, fetchSize);
          int len = writeBuffer(buffer, dest);
          if (len == 0) {
            break;
          }
          offset += len;
        }
      } finally {
        ClientUtils.putBackSyncClient(client);
      }
      dest.flush();
    }
  }
}
