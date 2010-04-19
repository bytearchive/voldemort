/*
 * Copyright 2008-2009 LinkedIn, Inc
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package voldemort.store.readonly.mr;

import java.io.IOException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.BytesWritable;
import org.apache.hadoop.mapred.FileInputFormat;
import org.apache.hadoop.mapred.FileOutputFormat;
import org.apache.hadoop.mapred.InputFormat;
import org.apache.hadoop.mapred.JobClient;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.SequenceFileOutputFormat;
import org.apache.log4j.Logger;

import voldemort.VoldemortException;
import voldemort.cluster.Cluster;
import voldemort.store.StoreDefinition;
import voldemort.utils.ByteUtils;
import voldemort.utils.Utils;
import voldemort.xml.ClusterMapper;
import voldemort.xml.StoreDefinitionsMapper;

/**
 * Builds a read-only voldemort store as a hadoop job from the given input data.
 * 
 */
public class HadoopStoreBuilder {

    public static final long MIN_CHUNK_SIZE = 1L;
    public static final long MAX_CHUNK_SIZE = (long) (1.9 * 1024 * 1024 * 1024);
    public static final int DEFAULT_BUFFER_SIZE = (int) 64 * 1024;

    private static final Logger logger = Logger.getLogger(HadoopStoreBuilder.class);

    private final Configuration config;
    private final Class<? extends AbstractHadoopStoreBuilderMapper<?, ?>> mapperClass;
    @SuppressWarnings("unchecked")
    private final Class<? extends InputFormat> inputFormatClass;
    private final Cluster cluster;
    private final StoreDefinition storeDef;
    private final int replicationFactor;
    private final long chunkSizeBytes;
    private final Path inputPath;
    private final Path outputDir;
    private final Path tempDir;
    private boolean doCheckSum = false;

    @SuppressWarnings("unchecked")
    public HadoopStoreBuilder(Configuration conf,
                              Class<? extends AbstractHadoopStoreBuilderMapper<?, ?>> mapperClass,
                              Class<? extends InputFormat> inputFormatClass,
                              Cluster cluster,
                              StoreDefinition storeDef,
                              int replicationFactor,
                              long chunkSizeBytes,
                              Path tempDir,
                              Path outputDir,
                              Path inputPath) {
        super();
        this.config = conf;
        this.mapperClass = Utils.notNull(mapperClass);
        this.inputFormatClass = Utils.notNull(inputFormatClass);
        this.inputPath = inputPath;
        this.cluster = Utils.notNull(cluster);
        this.storeDef = Utils.notNull(storeDef);
        this.replicationFactor = replicationFactor;
        this.chunkSizeBytes = chunkSizeBytes;
        this.tempDir = tempDir;
        this.outputDir = Utils.notNull(outputDir);
        if(chunkSizeBytes > MAX_CHUNK_SIZE || chunkSizeBytes < MIN_CHUNK_SIZE)
            throw new VoldemortException("Invalid chunk size, chunk size must be in the range "
                                         + MIN_CHUNK_SIZE + "..." + MAX_CHUNK_SIZE);
    }

    /**
     * Create the store builder
     * 
     * @param conf A base configuration to start with
     * @param mapperClass The class to use as the mapper
     * @param inputFormatClass The input format to use for reading values
     * @param cluster The voldemort cluster for which the stores are being built
     * @param storeDef The store definition of the store
     * @param replicationFactor The replication factor to use for storing the
     *        built store.
     * @param chunkSizeBytes The size of the chunks used by the read-only store
     * @param tempDir The temporary directory to use in hadoop for intermediate
     *        reducer output
     * @param outputDir The directory in which to place the built stores
     * @param inputPath The path from which to read input data
     */
    @SuppressWarnings("unchecked")
    public HadoopStoreBuilder(Configuration conf,
                              Class<? extends AbstractHadoopStoreBuilderMapper<?, ?>> mapperClass,
                              Class<? extends InputFormat> inputFormatClass,
                              Cluster cluster,
                              StoreDefinition storeDef,
                              int replicationFactor,
                              long chunkSizeBytes,
                              Path tempDir,
                              Path outputDir,
                              Path inputPath,
                              boolean doCheckSum) {
        this(conf,
             mapperClass,
             inputFormatClass,
             cluster,
             storeDef,
             replicationFactor,
             chunkSizeBytes,
             tempDir,
             outputDir,
             inputPath);
        this.doCheckSum = doCheckSum;

    }

    /**
     * Run the job
     */
    public void build() {
        JobConf conf = new JobConf(config);
        conf.setInt("io.file.buffer.size", DEFAULT_BUFFER_SIZE);
        conf.set("cluster.xml", new ClusterMapper().writeCluster(cluster));
        conf.set("stores.xml",
                 new StoreDefinitionsMapper().writeStoreList(Collections.singletonList(storeDef)));
        conf.setInt("store.output.replication.factor", replicationFactor);
        conf.setPartitionerClass(HadoopStoreBuilderPartitioner.class);
        conf.setMapperClass(mapperClass);
        conf.setMapOutputKeyClass(BytesWritable.class);
        conf.setMapOutputValueClass(BytesWritable.class);
        conf.setReducerClass(HadoopStoreBuilderReducer.class);
        conf.setInputFormat(inputFormatClass);
        conf.setOutputFormat(SequenceFileOutputFormat.class);
        conf.setOutputKeyClass(BytesWritable.class);
        conf.setOutputValueClass(BytesWritable.class);
        conf.setJarByClass(getClass());
        FileInputFormat.setInputPaths(conf, inputPath);
        conf.set("final.output.dir", outputDir.toString());
        FileOutputFormat.setOutputPath(conf, tempDir);

        try {

            FileSystem outputFs = outputDir.getFileSystem(conf);
            if(outputFs.exists(outputDir)) {
                throw new IOException("Final output directory already exists.");
            }

            // delete output dir if it already exists
            FileSystem tempFs = tempDir.getFileSystem(conf);
            tempFs.delete(tempDir, true);

            long size = sizeOfPath(tempFs, inputPath);
            int numChunks = Math.max((int) (storeDef.getReplicationFactor() * size
                                            / cluster.getNumberOfNodes() / chunkSizeBytes), 1);
            logger.info("Data size = " + size + ", replication factor = "
                        + storeDef.getReplicationFactor() + ", numNodes = "
                        + cluster.getNumberOfNodes() + ", chunk size = " + chunkSizeBytes
                        + ",  num.chunks = " + numChunks);
            conf.setInt("num.chunks", numChunks);
            int numReduces = cluster.getNumberOfNodes() * numChunks;
            conf.setNumReduceTasks(numReduces);
            logger.info("Number of reduces: " + numReduces);

            logger.info("Building store...");
            JobClient.runJob(conf);

            if(doCheckSum) {
                // Generate checksum for every node
                FileStatus[] nodes = outputFs.listStatus(outputDir);

                for(FileStatus node: nodes) {
                    if(node.isDir()) {
                        FileStatus[] storeFiles = outputFs.listStatus(node.getPath());
                        if(storeFiles != null) {
                            Arrays.sort(storeFiles, new IndexFileLastComparator());

                            byte[] buffer = new byte[DEFAULT_BUFFER_SIZE];
                            // Do a MD5ofMD5s - Similar to HDFS
                            MessageDigest checkSumGenerator = MessageDigest.getInstance("md5");
                            MessageDigest fileCheckSumGenerator = MessageDigest.getInstance("md5");

                            for(FileStatus status: storeFiles) {
                                if(!status.getPath().getName().startsWith(".")) {
                                    System.out.println("NODE3 = " + node.getPath() + " - "
                                                       + status.getPath());
                                    FSDataInputStream input = outputFs.open(status.getPath());

                                    while(true) {
                                        int read = input.read(buffer);
                                        if(read < 0)
                                            break;
                                        else if(read < DEFAULT_BUFFER_SIZE) {
                                            buffer = ByteUtils.copy(buffer, 0, read);
                                        }
                                        fileCheckSumGenerator.update(buffer);
                                    }
                                    checkSumGenerator.update(fileCheckSumGenerator.digest());
                                }
                            }

                            byte[] md5 = checkSumGenerator.digest();
                            FSDataOutputStream checkSumStream = outputFs.create(new Path(node.getPath(),
                                                                                         "checkSum.txt"));
                            checkSumStream.write(md5);
                            checkSumStream.flush();
                            checkSumStream.close();

                        }

                    }
                }
            }
        } catch(Exception e) {
            throw new VoldemortException(e);
        }

    }

    /**
     * A comparator that sorts index files last. This is required to maintain
     * the order while calculating checksum
     * 
     */
    static class IndexFileLastComparator implements Comparator<FileStatus> {

        public int compare(FileStatus fs1, FileStatus fs2) {
            // directories before files
            if(fs1.isDir())
                return fs2.isDir() ? 0 : -1;
            // index files after all other files
            else if(fs1.getPath().getName().endsWith(".index"))
                return fs2.getPath().getName().endsWith(".index") ? 0 : 1;
            // everything else is equivalent
            else
                return 0;
        }
    }

    private long sizeOfPath(FileSystem fs, Path path) throws IOException {
        long size = 0;
        FileStatus[] statuses = fs.listStatus(path);
        if(statuses != null) {
            for(FileStatus status: statuses) {
                if(status.isDir())
                    size += sizeOfPath(fs, status.getPath());
                else
                    size += status.getLen();
            }
        }
        return size;
    }
}
