package org.apache.flink.experiments;

import no.uio.ifi.ExperimentAPI;
import no.uio.ifi.SpeComm;
import no.uio.ifi.SpeSpecificAPI;
import no.uio.ifi.TracingFramework;
import org.apache.commons.io.FileUtils;
import org.apache.flink.api.common.JobID;
import org.apache.flink.api.common.functions.FlatMapFunction;
import org.apache.flink.api.common.functions.MapFunction;
import org.apache.flink.api.common.serialization.TypeInformationSerializationSchema;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.common.typeinfo.Types;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.api.java.typeutils.RowTypeInfo;
import java.nio.file.Path;

import org.apache.flink.client.cli.RunOptions;
import org.apache.flink.client.program.MiniClusterClient;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.configuration.JobManagerOptions;
import org.apache.flink.configuration.RestOptions;
import org.apache.flink.configuration.TaskManagerOptions;
import org.apache.flink.runtime.client.JobStatusMessage;
import org.apache.flink.runtime.jobgraph.JobGraph;
import org.apache.flink.runtime.jobgraph.SavepointRestoreSettings;
import org.apache.flink.runtime.minicluster.MiniCluster;
import org.apache.flink.runtime.minicluster.MiniClusterConfiguration;
import org.apache.flink.streaming.api.TimeCharacteristic;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.sink.SinkFunction;
import org.apache.flink.streaming.api.functions.source.SourceFunction;
import org.apache.flink.streaming.connectors.kafka.FlinkKafkaConsumer010;
import org.apache.flink.streaming.connectors.kafka.FlinkKafkaProducer010;
import org.apache.flink.streaming.connectors.kafka.internal.Kafka09Fetcher;
import org.apache.flink.table.api.EnvironmentSettings;
import org.apache.flink.table.api.Table;
import org.apache.flink.table.api.java.StreamTableEnvironment;
import org.apache.flink.table.functions.ScalarFunction;
import org.apache.flink.types.Row;
import org.apache.flink.util.Collector;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;
import scala.collection.script.Start;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.io.*;
import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.ExecutionException;

@SuppressWarnings("unchecked")
public class FlinkExperimentFramework implements ExperimentAPI, SpeSpecificAPI, Serializable {
	private static final Logger LOG = LoggerFactory.getLogger(FlinkExperimentFramework.class);
	static long timeLastRecvdTuple = 0;
	int batch_size;
	int pktsPublished;
	int interval_wait;
	final int TIMELASTRECEIVEDTHRESHOLD = 1000;  // ms
	boolean useRowtime = true;
	String trace_output_folder;
	StreamExecutionEnvironment env;
	StreamTableEnvironment tableEnv;
	EnvironmentSettings fsSettings = EnvironmentSettings.newInstance().useOldPlanner().inStreamingMode().build();
	Map<Integer, ArrayList<Row> > streamToTuples = new HashMap<>();
	Map<Integer, ArrayList<Map<String, Object>> > outputStreamIdToFetchQueries = new HashMap<>();
	Map<Integer, ArrayList<Map<String, Object>> > outputStreamIdToUpdateQueries = new HashMap<>();
	List<Map<String, Object>> fetchQueries = new ArrayList<>();
	ArrayList<Map<String, Object>> queries = new ArrayList<>();
	Map<Integer, Map<String, Object>> allSchemas = new HashMap<>();
	Map<Integer, TypeInformation<Row>> streamIdToTypeInfo = new HashMap<>();
	static Map<Integer, TypeInformationSerializationSchema<Row>> streamIdToSerializationSchema = new HashMap<>();
	Map<String, Integer> streamNameToId = new HashMap<>();
	Map<Integer, String> streamIdToName = new HashMap<>();
	Map<Integer, Map<String, Object>> nodeIdToIpAndPort = new HashMap<>();
	static Map<Integer, List<Integer>> streamIdToNodeIds = new HashMap<>();
	Map<Integer, DataStream<Row> > streamIdToDataStream = new HashMap<>();
	Map<Integer, List<Map<String, Object>>> datasetIdToTuples = new HashMap<>();
	Map<Integer, Boolean> printDataStream = new HashMap<>();
	Map<Integer, Map<String, Object>> queryIdToMapQuery = new HashMap<>();
	static TracingFramework tf = new TracingFramework();
	Producer<String, byte[]> producer;
	Map<Integer, SourceFunction<Row>> envSourceFunctions = new HashMap<>();

	SpeComm speComm;

	static List<Tuple2<Integer, Row>> incomingTupleBuffer = new ArrayList<>();
	static List<Tuple2<Integer, Row>> outgoingTupleBuffer = new ArrayList<>();
	static Map<Integer, Boolean> streamIdActive = new HashMap<>();
	static Map<Integer, Boolean> streamIdBuffer = new HashMap<>();

	long receivedTuples = 0;

	List<FlinkKafkaProducer010<Row> > sinkFunctions = new ArrayList<>();
	Map<Integer, SinkFunction<Row> > regularSinkFunctions = new HashMap<>();
	Map<String, FlinkKafkaProducer010<Row> > querySinkFunctions = new HashMap<>();
	//List<SourceFunction<Row> > sourceFunctions = new ArrayList<>();
	Thread threadRunningEnvironment;
	static int nodeId;
	Properties props;
	static Map<Integer, KafkaProducer> nodeIdToKafkaProducer = new HashMap<>();
	Map<Integer, Properties> nodeIdToProperties = new HashMap<>();
	List<Tuple2<Integer, Row>> all_tuples = new ArrayList<>();

	FlinkExperimentFramework() {
		props = new Properties();
		props.put("bootstrap.servers", "localhost:9092");
		props.put("acks", "all");
		props.put("retries", 0);
		props.put("batch.size", 16384);
		props.put("linger.ms", 1);
		props.put("buffer.memory", 33554432);
		props.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
		props.put("value.serializer", "org.apache.kafka.common.serialization.ByteArraySerializer");
	}

	public void setSpeComm(SpeComm speComm) {
		this.speComm = speComm;
	}

	public void setNodeId(int nodeId) {
		FlinkExperimentFramework.nodeId = nodeId;
		props.put("client.id", Integer.toString(nodeId));
		producer = new KafkaProducer<>(props);
	}

	public void SetTraceOutputFolder(String f) {this.trace_output_folder = f;}

	@Override
	public String SetTupleBatchSize(int size) {
		batch_size = size;
		return "Success";
	}

	@Override
	public String SetIntervalBetweenTuples(int interval) {
		interval_wait = interval;
		return "Success";
	}

	@Override
	public String SetNidToAddress(Map<Integer, Map<String, Object> > newNodeIdToIpAndPort) {
		//System.out.println("SetNidToAddress: " + newNodeIdToIpAndPort);
		nodeIdToIpAndPort = newNodeIdToIpAndPort;
		Properties properties = new Properties();
		for (Object key : props.keySet()) {
			properties.put(key, props.get(key));
		}

		for (int nodeId : nodeIdToIpAndPort.keySet()) {
			String ip = (String) nodeIdToIpAndPort.get(nodeId).get("ip");
			properties.put("bootstrap.servers", ip+":9092");
			nodeIdToProperties.put(nodeId, properties);
			nodeIdToKafkaProducer.put(nodeId, new KafkaProducer<>(properties));

			if (nodeId != FlinkExperimentFramework.nodeId) {
				// Establish coordinator connection with node
				int port = (int) newNodeIdToIpAndPort.get(nodeId).get("spe-coordinator-port");
				try {
					this.speComm.ConnectToSpeCoordinator(nodeId, ip, port);
				} catch (Exception e) {
					e.printStackTrace();
					System.err.println("Failed to connect to Node " + nodeId + "'s SPE coordinator");
					System.exit(20);
				}
			}
		}
		return "Success";
	}

	@Override
	public String AddNextHop(List<Integer> streamIds, List<Integer> nodeIds) {
		for (int streamId : streamIds) {
			if (!streamIdToNodeIds.containsKey(streamId)) {
				streamIdToNodeIds.put(streamId, new ArrayList<>());
			}

			streamIdToNodeIds.get(streamId).addAll(nodeIds);
			/*for (Map<String, Object> schema : allSchemas.values()) {
				if (schema.get("stream-id").equals(streamId)) {
					String stream_name = (String) schema.get("name");
					DataStream<Row> ds = streamIdToDataStream.get(streamId);
					if (ds == null) {
						AddKafkaConsumer(schema);
					}
					ds = streamIdToDataStream.get(streamId);
					for (int otherNodeId : streamIdToNodeIds.get(streamId)) {
						String topic = stream_name + "-" + otherNodeId;
						FlinkKafkaProducer010<Row> p = new FlinkKafkaProducer010<>(topic, streamIdToSerializationSchema.get(streamId), nodeIdToProperties.get(otherNodeId));
						sinkFunctions.add(p);
						ds.addSink(p);
					}
					break;
				}
			}*/
		}
		return "Success";
	}

	public String WriteStreamToCsv(int stream_id, String csvFolder) {
		Map<String, Object> schema = allSchemas.get(stream_id);
		assert schema != null;
		DataStream<Row> ds = streamIdToDataStream.get(stream_id);
		if (ds == null) {
			AddKafkaConsumer(schema);
		}
		ds = streamIdToDataStream.get(stream_id);
		int cnt = 1;
		boolean finished = false;
		while (!finished) {
			String path = System.getenv().get("EXPOSE_PATH") + "/" + csvFolder + "/flink/" + cnt;
			Path p = Paths.get(path);
			if (Files.exists(p)) {
				++cnt;
				continue;
			}
			ds.writeAsText(path);
			finished = true;
		}
		return "Success";
	}

	volatile static int[] cnt = {0};

	private void AddKafkaConsumer(Map<String, Object> schema) {
		int stream_id = (int) schema.get("stream-id");
		String stream_name = (String) schema.get("name");
		String topic = stream_name + "-" + FlinkExperimentFramework.nodeId;
		System.out.println("Subscribing to kafka topic " + topic);
		FlinkKafkaConsumer010<Row> consumer = new FlinkKafkaConsumer010<>(topic, streamIdToSerializationSchema.get(stream_id), this.props, tf);
		DataStream<Row> ds = env.addSource(consumer).returns(streamIdToTypeInfo.get(stream_id));
		SinkFunction<Row> sf = new SinkFunction<Row>() {
			private final Logger LOG = LoggerFactory.getLogger(SinkFunction.class);
			@Override
			public void invoke(Row value) {
				long newTime = System.currentTimeMillis();
				++cnt[0];
				//System.out.println("Received tuple " + cnt[0]);
				// We only log once every maximum one second, to avoid too many tracepoints
				if (newTime - timeLastRecvdTuple > TIMELASTRECEIVEDTHRESHOLD) {
					LOG.info("Received tuple {}", cnt[0]);
					System.out.println("Received tuple " + cnt[0]);
					timeLastRecvdTuple = newTime;
				}
			}
		};
		regularSinkFunctions.put(stream_id, sf);
		ds.addSink(sf);
		StringBuilder fields = new StringBuilder();
		for (Map<String, String> attribute : (List<Map<String, String> >) schema.get("tuple-format")) {
			if (!fields.toString().equals("")) {
				fields.append(", ");
			}

			fields.append(attribute.get("name"));
		}
		String fields_str = fields.toString();
		if (!useRowtime) {
			fields_str += ", eventTime.proctime";
		}
		tableEnv.registerDataStream(stream_name, ds, fields_str);

		class CustomFlatMapFunction implements FlatMapFunction<Row, Row> {
			int stream_id;

			@Override
			public void flatMap(Row row, Collector<Row> out) {
				/*if (stream_id == 0) {
					String raw_stream_id_list = (String) event.getData()[0];
					List<Integer> stream_id_list = new ArrayList<>();
					for (String string_stream_id : raw_stream_id_list.split(", ")) {
						stream_id_list.add(Integer.parseInt(string_stream_id));
					}
					int sending_node_id = (int) event.getData()[1];
					if (!nodeIdToStoppedStreams.containsKey(sending_node_id)) {
						nodeIdToStoppedStreams.put(sending_node_id, new ArrayList<>());
					}
					nodeIdToStoppedStreams.get(sending_node_id).addAll(stream_id_list);
				} else */if (streamIdActive.getOrDefault(stream_id, true)) {
					out.collect(row);
				} else if (streamIdBuffer.getOrDefault(stream_id, false)) {
					incomingTupleBuffer.add(new Tuple2<>(stream_id, row));
				}
			}
		}
		CustomFlatMapFunction flatMapFunction = new CustomFlatMapFunction();
		flatMapFunction.stream_id = stream_id;
		ds = ds.flatMap(flatMapFunction);
		this.streamIdToDataStream.put(stream_id, ds);
	}

	void CastTuplesCorrectTypes(List<Map<String, Object>> tuples, Map<String, Object> schema) {
		List<Map<String, String>> tuple_format = (ArrayList<Map<String, String>>) schema.get("tuple-format");
		for (Map<String, Object> tuple : tuples) {
			List<Map<String, Object>> attributes = (List<Map<String, Object>>) tuple.get("attributes");
			for (int i = 0; i < tuple_format.size(); i++) {
				Map<String, String> attribute_format = tuple_format.get(i);
				Map<String, Object> attribute = attributes.get(i);
				switch (attribute_format.get("type")) {
					case "string":
						attribute.put("value", attribute.get("value").toString());
						break;
					case "bool":
						attribute.put("value", Boolean.valueOf(attribute.get("value").toString()));
						break;
					case "int":
						attribute.put("value", Integer.parseInt(attribute.get("value").toString()));
						break;
					case "float":
						attribute.put("value", Float.parseFloat(attribute.get("value").toString()));
						break;
					case "double":
						attribute.put("value", Double.parseDouble(attribute.get("value").toString()));
						break;
					case "long":
						attribute.put("value", Long.parseLong(attribute.get("value").toString()));
						break;
					case "long-timestamp":
						attribute.put("value", new Timestamp(Long.parseLong(attribute.get("value").toString())));
						break;
					case "timestamp":
						attribute.put("value", Timestamp.valueOf(attribute.get("value").toString()));
						break;
					default:
						throw new RuntimeException("Invalid attribute type in dataset definition");
				}
			}
		}
	}

	@Override
	public String SendDsAsStream(Map<String, Object> ds, int iterations, boolean realism) {
		//System.out.println("Processing dataset");
		int ds_id = (int) ds.get("id");
		List<Map<String, Object>> tuples = datasetIdToTuples.get(ds_id);
		if (tuples == null) {
			Map<String, Object> map = GetMapFromYaml(ds);
			List<Map<String, Object>> raw_tuples = (List<Map<String, Object>>) map.get("cepevents");
			Map<Integer, List<Map<String, Object>>> ordered_tuples = new HashMap<>();
			//int i = 0;
			// Add order to tuples and place them in ordered_tuples
			for (Map<String, Object> tuple : raw_tuples) {
				//tuple.put("_order", i++);
				int tuple_stream_id = (int) tuple.get("stream-id");
				if (ordered_tuples.get(tuple_stream_id) == null) {
					ordered_tuples.put(tuple_stream_id, new ArrayList<>());
				}
				ordered_tuples.get(tuple_stream_id).add(tuple);
			}

			// Fix the type of the tuples in ordered_tuples
			for (int stream_id : ordered_tuples.keySet()) {
				Map<String, Object> schema = allSchemas.get(stream_id);
				CastTuplesCorrectTypes(ordered_tuples.get(stream_id), schema);
			}

			// Sort the raw_tuples by their order
			/*raw_tuples.sort((lhs, rhs) -> {
				int lhs_order = (int) lhs.get("_order");
				int rhs_order = (int) rhs.get("_order");
				return Integer.compare(lhs_order, rhs_order);
			});*/

			datasetIdToTuples.put(ds_id, raw_tuples);
			tuples = raw_tuples;
		}
		/*double prevTimestamp = 0;
		//System.out.println("Ready to transmit tuples");
		long prevTime = System.nanoTime();
		boolean realism = (boolean) ds.getOrDefault("realism", false) && schema.containsKey("rowtime-column");
		for (Map<String, Object> tuple : tuples) {
			AddTuples(tuple, 1);

			if (realism) {
				Map<String, Object> rowtime_column = (Map<String, Object>) schema.get("rowtime-column");
				double timestamp = 0;
				for (Map<String, Object> attribute : (List<Map<String, Object>>) tuple.get("attributes")) {
					if (attribute.get("name").equals(rowtime_column.get("column"))) {
						int nanoseconds_per_tick = (int) rowtime_column.get("nanoseconds-per-tick");
						timestamp = (double) attribute.get("value") * nanoseconds_per_tick;
						if (prevTimestamp == 0) {
							prevTimestamp = timestamp;
						}
						break;
					}
				}
				double time_diff_tuple = timestamp - prevTimestamp;
				long time_diff_real = System.nanoTime() - prevTime;
				while (time_diff_real < time_diff_tuple) {
					time_diff_real = System.nanoTime() - prevTime;
				}

				prevTimestamp = timestamp;
				prevTime = System.nanoTime();
			}
		}

		if (!realism) {
			ProcessTuples(tuples.size());
		}*/

		for (Map<String, Object> tuple : tuples) {
			AddTuples(tuple, 1);
		}
		ProcessTuples(tuples.size());
		return "Success";
	}

	public String AddTuples(Map<String, Object> tuple, int quantity) {
		int stream_id = (int) tuple.get("stream-id");
		ArrayList<Map<String, Object> > attributes = (ArrayList<Map<String, Object> >) tuple.get("attributes");
		Row new_tuple = new Row(attributes.size());
		for (int i = 0; i < attributes.size(); i++) {
			Map<String, Object> attribute = attributes.get(i);
			new_tuple.setField(i, attribute.get("value"));
		}
		/*int stream_id = (int) tuple.get("stream-id");
		if (!streamToTuples.containsKey(stream_id)) {
			streamToTuples.put(stream_id, new ArrayList<>());
		}

		for (int i = 0; i < quantity; i++) {
			streamToTuples.get(stream_id).add(new_tuple);
		}*/
		all_tuples.add(new Tuple2<Integer, Row>(stream_id, new_tuple));
		return "Success";
	}

	@Override
	public String AddSchemas(List<Map<String, Object>> schemas) {
		for (Map<String, Object> schema : schemas) {
			int stream_id = (int) schema.get("stream-id");
			final String stream_name = (String) schema.get("name");
			streamIdToName.put(stream_id, stream_name);
			streamNameToId.put(stream_name, stream_id);
			allSchemas.put(stream_id, schema);
			ArrayList<Map<String, String>> tuple_format = (ArrayList<Map<String, String>>) schema.get("tuple-format");
			TypeInformation<?>[] typeInformations = new TypeInformation[tuple_format.size()];
			int pos = -1;
			String rowtime_column = null;
			if (useRowtime && schema.containsKey("rowtime-column")) {
				rowtime_column = ((Map<String, String>)schema.get("rowtime-column")).get("column");
			}
			for (Map<String, String> attribute : tuple_format) {
				pos += 1;
				if (useRowtime && attribute.get("name") != null && attribute.get("name").equals(rowtime_column)) {
					attribute.put("name", "eventTime.rowtime");
				}
				switch (attribute.get("type")) {
					case "string":
						typeInformations[pos] = Types.STRING;
						break;
					case "bool":
						typeInformations[pos] = Types.BOOLEAN;
						break;
					case "int":
						typeInformations[pos] = Types.INT;
						break;
					case "float":
						typeInformations[pos] = Types.FLOAT;
						break;
					case "double":
						typeInformations[pos] = Types.DOUBLE;
						break;
					case "long":
						typeInformations[pos] = Types.LONG;
						break;
					case "long-timestamp":
					case "timestamp":
						typeInformations[pos] = Types.SQL_TIMESTAMP;
						break;
					default:
						throw new RuntimeException("Invalid attribute type in dataset definition");
				}
			}

			if (env == null) {
				env = StreamExecutionEnvironment.getExecutionEnvironment();
				env.getConfig().disableSysoutLogging();
				if (useRowtime) {
					env.setStreamTimeCharacteristic(TimeCharacteristic.EventTime);
				} else {
					env.setStreamTimeCharacteristic(TimeCharacteristic.ProcessingTime);
				}
				tableEnv = StreamTableEnvironment.create(env, fsSettings);
				tableEnv.registerFunction("DOLTOEUR", new DolToEur());
			}

			streamIdToTypeInfo.put(stream_id, new RowTypeInfo(typeInformations));
			streamIdToSerializationSchema.put(stream_id, new TypeInformationSerializationSchema<>(streamIdToTypeInfo.get(stream_id), env.getConfig()));
		}
		return "Success";
	}

	public void DeployQueries() {
		for (Map<String, Object> query : fetchQueries) {
			int outputStreamId = (int) query.get("output-stream-id");
			String outputStreamName = streamIdToName.get(outputStreamId);
			Map<String, Object> output_schema = this.allSchemas.get(outputStreamId);
			String sql_query = ((Map<String, String>) query.get("sql-query")).get("flink");
			if (sql_query == null) {
				continue;
			}
			Table result = tableEnv.sqlQuery(sql_query);
			if (!(boolean) output_schema.getOrDefault("registered", false) &&
				(boolean) output_schema.getOrDefault("intermediary-stream", false)) {
				output_schema.put("registered", true);
				tableEnv.registerTable(this.streamIdToName.get(outputStreamId), result);
			} else {
				// Need to filter out the processing time element
				DataStream<Row> ds =
						tableEnv.toRetractStream(result, streamIdToTypeInfo.get(outputStreamId))
								.map((MapFunction<Tuple2<Boolean, Row>, Row>) value -> value.f1);
				if (streamIdToNodeIds.containsKey(outputStreamId)) {
					class CustomFlatMapFunction implements FlatMapFunction<Row, Object> {
						public final int outputStreamId;
						CustomFlatMapFunction(int outputStreamId) {
							this.outputStreamId = outputStreamId;
						}

						@Override
						public void flatMap(Row row, Collector<Object> out) {
							System.out.println("Produced tuple from query");
							if (streamIdActive.getOrDefault(outputStreamId, true)) {
								TypeInformationSerializationSchema<Row> serializationSchema = streamIdToSerializationSchema.get(outputStreamId);
								for (int otherNodeId : streamIdToNodeIds.get(outputStreamId)) {
									String topic = outputStreamName + "-" + otherNodeId;
									nodeIdToKafkaProducer.get(otherNodeId).send(new ProducerRecord<>(topic, serializationSchema.serialize(row)));
								}
							} else if(streamIdBuffer.getOrDefault(outputStreamId, false)) {
								outgoingTupleBuffer.add(new Tuple2<>(outputStreamId, row));
							}
						}
					}

					CustomFlatMapFunction flatMapFunction = new CustomFlatMapFunction(outputStreamId);
					ds.flatMap(flatMapFunction);
				}
			}
		}
	}

	@Override
	public String DeployQueries(Map<String, Object> query) {
		int outputStreamId = (int) query.get("output-stream-id");
		int query_id = (int) query.get("id");
		printDataStream.put(outputStreamId, (boolean) query.getOrDefault("print", false));
		tf.traceEvent(221, new Object[]{query_id});
		fetchQueries.add(query);
		queryIdToMapQuery.put(query_id, query);
		return "Success";
	}

	MiniCluster cluster;
	MiniClusterClient client;
	JobID jobID;
	String savepointPath;
	SavepointRestoreSettings savepointRestoreSettings = null;
	@Override
	public String StartRuntimeEnv() {
		/*if (interrupted) {
			System.out.println("Still waiting for the runtime environment to interrupt!");
			return "Error, runtime environment has not exited from its previous execution";
		}*/

		/*Configuration mcConfig = new Configuration();
		mcConfig.setString(TaskManagerOptions.MANAGED_MEMORY_SIZE, "80m");
		mcConfig.setInteger(JobManagerOptions.PORT, 0);
		mcConfig.setString(RestOptions.BIND_PORT, "0");
		MiniClusterConfiguration config = new MiniClusterConfiguration.Builder()
				.setNumTaskManagers(2)
				.setNumSlotsPerTaskManager(2)
				.setConfiguration(mcConfig).build();
		MiniCluster cluster = new MiniCluster(config);
		try {
			cluster.start();
		} catch (Exception e) {
			e.printStackTrace();
		}
		MiniClusterClient client = new MiniClusterClient(new Configuration(), cluster);*/

		if (threadRunningEnvironment != null && threadRunningEnvironment.isAlive()) {
			//throw new RuntimeException("The execution environment is already running. " +
			//	                   	   "Stop it with stopRuntimeEnv before running it again.");
			//System.out.println("The execution environment is already running. " +
			//	"Stop it with stopRuntimeEnv before running it again.");
			return "Environment already running";
		}
		DeployQueries();
		//deployedQueries = 0;
		//outputStreamIdToFetchQueries.clear();
		//outputStreamIdToUpdateQueries.clear();
		threadRunningEnvironment = new Thread(() -> {
			//System.out.println("Starting environment");
			Kafka09Fetcher.timeLastRecvdTuple = 0;
			Kafka09Fetcher.receivedTuples = 0;
			timeLastRecvdTuple = 0;
			receivedTuples = 0;
			if (savepointRestoreSettings != null) {
				env.getStreamGraph().getJobGraph().setSavepointRestoreSettings(savepointRestoreSettings);
			}
			try {
				env.execute();
			} catch (Exception e) {
				if (!interrupted) {
					// This interrupt was not because of us
					e.printStackTrace();
				}
				System.out.println("Stopping the execution environment");
			}
			Kafka09Fetcher.timeLastRecvdTuple = 0;
			Kafka09Fetcher.receivedTuples = 0;
			timeLastRecvdTuple = 0;
			receivedTuples = 0;
		});
		interrupted = false;
		threadRunningEnvironment.start();

		/*cluster = env.getMiniCluster();
		while (cluster == null) {
			cluster = env.getMiniCluster();
		}*/
		//client = new MiniClusterClient(new Configuration(), cluster);

		try {
			System.out.println("Client Job IDs:");
			assert client.listJobs().get().size() == 1;
			/*for (JobStatusMessage msg : client.listJobs().get()) {
				// I assume we only have a single job running
				this.jobID = msg.getJobId();
			}*/
			System.out.println("Job ID: " + jobID);
			//System.out.println("Cluster Job status: " + cluster.getJobStatus(jobID).get());
		} catch (InterruptedException | ExecutionException e) {
			e.printStackTrace();
		}
		/*try {
			savepointPath = client.triggerSavepoint(this.jobID, System.getenv("FLINK_BINARIES") + "/savepoints/created_savepoints").get();
		} catch (InterruptedException | ExecutionException e) {
			e.printStackTrace();
		}*/
		return "Success";
	}

	boolean interrupted = false;
	static int cnt3 = 0;
	@Override
	public String StopRuntimeEnv() {
		tf.traceEvent(101);
		//SavepointRestoreSettings savepointSettings = SavepointRestoreSettings.forPath(savepointPath);
		//System.out.println("savepointSettings.restoreSavepoint() success: " + savepointSettings.restoreSavepoint());
		//client.triggerSavepoint(this.jobID, System.getenv("FLINK_BINARIES") + "/savepoints/created_savepoints");

		/*try {
			client.cancel(jobID);
		} catch (Exception e) {
			e.printStackTrace();
		}*/

		threadRunningEnvironment.interrupt();
		threadRunningEnvironment = null;

		for (int stream_id : streamIdToDataStream.keySet()) {
			DataStream<Row> ds = streamIdToDataStream.get(stream_id);
			ds.addSink(regularSinkFunctions.get(stream_id));
		}

		tf.writeTraceToFile(this.trace_output_folder, this.getClass().getSimpleName());
		return "Success";
	}

	private Map<String, Object> GetMapFromYaml(Map<String, Object> ds) {
		FileInputStream fis = null;
		Yaml yaml = new Yaml();
		String dataset_path = System.getenv().get("EXPOSE_PATH") + "/" + ds.get("file");
		try {
			fis = new FileInputStream(dataset_path);
		} catch (FileNotFoundException e) {
			e.printStackTrace();
			System.exit(15);
		}
		return yaml.load(fis);
	}

	int tupleCnt = 0;
	public String ProcessTuples(int number_tuples) {
		System.out.println("Processing " + number_tuples);
		for (Tuple2<Integer, Row> tuple : all_tuples) {
			int stream_id = tuple.f0;
			Row row = tuple.f1;
			TypeInformationSerializationSchema<Row> serializationSchema = streamIdToSerializationSchema.get(stream_id);
			String stream_name = (String) allSchemas.get(stream_id).get("name");
			for (int otherNodeId : streamIdToNodeIds.getOrDefault(stream_id, new ArrayList<>())) {
				String topicName = stream_name + "-" + otherNodeId;
				//for (Row tuple : streamToTuples.get(stream_id)) {
				if (++tupleCnt % 100000 == 0) {
					System.out.println( System.nanoTime() + ": Sending tuple " + (++tupleCnt) + " to node " + otherNodeId + " with topic " + topicName);
				}
				nodeIdToKafkaProducer.get(otherNodeId).send(new ProducerRecord<>(topicName, serializationSchema.serialize(row)));
				//}
			}
		}

		all_tuples.clear();
		pktsPublished = 0;
		return "Success";
	}

	@Override
	public String ClearQueries() {
		tf.traceEvent(222);
		queries.clear();
		return "Success";
	}

	@Override
	public String EndExperiment() {
		tf.writeTraceToFile(this.trace_output_folder, this.getClass().getSimpleName());
		return "Success";
	}

	@Override
	public String AddTpIds(List<Object> tracepointIds) {
		for (int tracepointId : (List<Integer>) (List<?>) tracepointIds) {
			tf.addTracepoint(tracepointId);
		}
		return "Success";
	}

	/* Cluster version of the RetEndOfStream method
	@Override
	public String RetEndOfStream(int milliseconds) {
		milliseconds += TIMELASTRECEIVEDTHRESHOLD;  // We add waiting time because we don't log every received tuple
		File file = new File(System.getenv("FLINK_BINARIES") + "/log/FlinkWorker.log");
		try {
			// First we wait until the log file is not empty
			// Importantly, the log file may only contain the logs for when having received tuples
			long firstLength = file.length();
			while (file.length() == 0) {
				firstLength = file.length();
				Thread.sleep(milliseconds);
			}
			while (file.length() != firstLength) {
				firstLength = file.length();
				Thread.sleep(milliseconds);
			}
		} catch (InterruptedException e) {
			e.printStackTrace();
		}

		return "Success";
	}*/

	@Override
	public String RetEndOfStream(int milliseconds) {
		long time_diff;
		do {
			try {
				Thread.sleep(milliseconds);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
			long cur_time = System.currentTimeMillis();
			time_diff = cur_time - Kafka09Fetcher.timeLastRecvdTuple;
			System.out.println("RetEndOfStream, time_diff: " + time_diff + ", cur-time: " + cur_time + ", timeLastRecvdTuple: " + Kafka09Fetcher.timeLastRecvdTuple);
		} while (time_diff < milliseconds || Kafka09Fetcher.timeLastRecvdTuple == 0);
		return Long.toString(time_diff);
	}

	@Override
	public String RetReceivedXTuples(int numberTuples) {
		do {
			try {
				Thread.sleep(2000);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		} while (Kafka09Fetcher.receivedTuples < numberTuples);
		return "Success";
	}

	@Override
	public String Wait(int milliseconds) {
		try {
			Thread.sleep(milliseconds);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}

		return "Success";
	}

	@Override
	public String TraceTuple(int tracepointId, List<String> arguments) {
		System.out.println("TraceTuple, tracepointId: " + tracepointId + ", arguments: " + arguments);
		tf.traceEvent(tracepointId, arguments.toArray());
		System.out.println("After tracing");
		return "Success";
	}

	@Override
	public String MoveQueryState(int query_id, int new_host) {
		// Savepoint here, and send it to the new host
		// We'll send it as a byte array, or something like that.
		// In LoadQueryState, we'll restore that savepoint
		try {
			savepointPath = client.triggerSavepoint(this.jobID, System.getenv("FLINK_BINARIES") + "/savepoints/created_savepoints").get() + "/_metadata";
			savepointPath = savepointPath.split("file:")[1];
		} catch (InterruptedException | ExecutionException e) {
			e.printStackTrace();
		}
		// Read savepointPath and send it as a byte array
		// LoadQueryState() will write the array to file and restore it
		byte[] snapshot = null;
		System.out.println("Moving query state from savepoint " + savepointPath);
		String[] path = savepointPath.split("/");
		String parentFolderName = path[path.length-2];
		File file = new File(savepointPath);
		while (!file.exists()) {
			System.out.println("Waiting for savepoint file " + savepointPath + " to be created");
			try {
				System.out.println("Waiting for savepoint file (canonical) " + file.getCanonicalPath() + " to be created");
			} catch (IOException e) {
				e.printStackTrace();
			}
			System.out.println("Waiting for savepoint file (absolute) " + file.getAbsolutePath() + " to be created");
			try {
				Thread.sleep(2000);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}
		try {
			snapshot = FileUtils.readFileToByteArray(file);
		} catch (IOException e) {
			e.printStackTrace();
		}
		Map<String, Object> task = new HashMap<>();
		task.put("task", "loadQueryState");
		List<Object> task_args = new ArrayList<>();
		task_args.add(snapshot);
		Map<String, Object> map_query = queryIdToMapQuery.get(query_id);
		task_args.add(map_query);
		task_args.add(parentFolderName);
		task_args.add(streamIdToNodeIds);
		task.put("arguments", task_args);
		task.put("node", Collections.singletonList(new_host));
		speComm.speCoordinatorComm.SendToSpe(task);

		/*byte[] snapshot = queryIdToSiddhiAppRuntime.get(query_id).snapshot();
		// If tuples are currently being processed, the snapshot will contain them.
		// If a new tuple is received between the snapshot is received above and the
		// runtime environment is shut down below, there is a lock that prevents a conflict
		// from occurring. The lock currently has flaws that need to be fixed.
		queryIdToSiddhiAppRuntime.get(query_id).shutdown();
		queryIdToSiddhiAppRuntime.remove(query_id);
		if (queryIdToSiddhiAppRuntime.isEmpty()) {
			StartSiddhiAppRuntime();
		}
		Map<String, Object> task = new HashMap<>();
		task.put("task", "loadQueryState");
		List<Object> task_args = new ArrayList<>();
		task_args.add(snapshot);
		Map<String, Object> map_query = queryIdToMapQuery.get(query_id);
		task_args.add(map_query);
		Map<Integer, List<Integer>> streamIdsToNodeIds = queryIdToStreamIdToNodeIds.getOrDefault(query_id, new HashMap<>());
		task_args.add(streamIdsToNodeIds);
		task.put("arguments", task_args);
		task.put("node", Collections.singletonList(new_host));
		speComm.speCoordinatorComm.SendToSpe(task);*/
		return "Success";
	}

	public String LoadQueryState(byte[] snapshot, Map<String, Object> map_query, String savepointName, Map<Integer, List<Integer>> stream_ids_to_source_node_ids) {
		int outputStreamId = (int) map_query.get("output-stream-id");
		// Write snapshot to savepoints/received_savepoints/savepointName
		savepointPath = System.getenv("FLINK_BINARIES") + "/savepoints/received_savepoints/" + savepointName + "/_metadata";
		try {
			FileUtils.writeByteArrayToFile(new File(savepointPath), snapshot);
		} catch (IOException e) {
			e.printStackTrace();
		}

		System.out.println("Restoring query state from savepoint " + savepointPath);
		// Restore the snapshot
		savepointRestoreSettings = SavepointRestoreSettings.forPath(savepointPath, true);
		System.out.println("Restore: " + savepointRestoreSettings.restoreSavepoint());
		System.out.println("Allow restore state: " + savepointRestoreSettings.allowNonRestoredState());
		try {
			threadRunningEnvironment.interrupt();
		} catch (Exception e) {
			e.printStackTrace();
		}
		threadRunningEnvironment = new Thread(() -> {
			env.getStreamGraph().getJobGraph().setSavepointRestoreSettings(savepointRestoreSettings);
			try {
				env.execute();
			} catch (Exception e) {
				if (!interrupted) {
					// This interrupt was not because of us
					e.printStackTrace();
				}
				System.out.println("Stopping the execution environment");
			}
		});
		threadRunningEnvironment.start();
		savepointRestoreSettings = null;

		DeployQueries(map_query);
		streamIdToNodeIds.computeIfAbsent(outputStreamId, k -> new ArrayList<>());
		streamIdToNodeIds.get(outputStreamId).addAll(stream_ids_to_source_node_ids.get(outputStreamId));

		/*queryIdToStreamIdToNodeIds.put(query_id, stream_ids_to_source_node_ids);
		String query = (String) ((Map<String, Object>) map_query.get("sql-query")).get("siddhi");
		try {
			StringBuilder schemasString = new StringBuilder();
			for (String siddhiSchema : siddhiSchemas.values()) {
				schemasString.append(siddhiSchema);
			}
			queryIdToSiddhiAppRuntime.put(query_id, siddhiManager.createSiddhiAppRuntime(schemasString.toString() + "\n" + query));
			int output_stream_id = queryIdToOutputStreamId.get(query_id);
			String output_stream_name = streamIdToName.get(output_stream_id);
			queryIdToSiddhiAppRuntime.get(query_id).addCallback(output_stream_name, streamIdToStreamCallbacks.get(output_stream_id));
			queryIdToSiddhiAppRuntime.get(query_id).start();
			queryIdToSiddhiAppRuntime.get(query_id).restore(snapshot);
		} catch (CannotRestoreSiddhiAppStateException e) {
			e.printStackTrace();
			System.exit(21);
		}*/

		ResumeStream(new ArrayList<>(stream_ids_to_source_node_ids.keySet()));
		return "Success";
	}

	@Override
	public String MoveStaticQueryState(int query_id, int new_host) {
		return null;
	}

	@Override
	public String MoveDynamicQueryState(int query_id, int new_host) {
		return null;
	}

	@Override
	public String ResumeStream(List<Integer> stream_id_list) {
		for (int stream_id : stream_id_list) {
			streamIdActive.put(stream_id, true);
		}

		for (Tuple2<Integer, Row> incoming_tuple : incomingTupleBuffer) {
			// Send tuple on Kafka topic to myself
			int outputStreamId = incoming_tuple.f0;
			String outputStreamName = streamIdToName.get(outputStreamId);
			Row row = incoming_tuple.f1;
			// Send tuple to subscribers
			TypeInformationSerializationSchema<Row> serializationSchema = streamIdToSerializationSchema.get(outputStreamId);
			String topic = outputStreamName + "-" + nodeId;
			nodeIdToKafkaProducer.get(nodeId).send(new ProducerRecord<>(topic, serializationSchema.serialize(row)));
		}

		for (Tuple2<Integer, Row> outgoing_tuple : outgoingTupleBuffer) {
			int outputStreamId = outgoing_tuple.f0;
			String outputStreamName = streamIdToName.get(outputStreamId);
			Row row = outgoing_tuple.f1;
			// Send tuple to subscribers
			TypeInformationSerializationSchema<Row> serializationSchema = streamIdToSerializationSchema.get(outputStreamId);
			for (int otherNodeId : streamIdToNodeIds.get(outputStreamId)) {
				String topic = outputStreamName + "-" + otherNodeId;
				nodeIdToKafkaProducer.get(otherNodeId).send(new ProducerRecord<>(topic, serializationSchema.serialize(row)));
			}
		}
		return "Success";
	}

	@Override
	public String StopStream(List<Integer> stream_id_list) {
		for (int stream_id : stream_id_list) {
			streamIdActive.put(stream_id, false);
		}
		return "Success";
	}

	@Override
	public String BufferStream(List<Integer> stream_id_list) {
		for (int stream_id : stream_id_list) {
			streamIdBuffer.put(stream_id, false);
		}
		return "Success";
	}

	@Override
	public String BufferAndStopStream(List<Integer> stream_id_list) {
		BufferStream(stream_id_list);
		StopStream(stream_id_list);
		return "Success";
	}

	@Override
	public String BufferStopAndRelayStream(List<Integer> stream_id_list, List<Integer> old_host_list, List<Integer> new_host_list) {
		BufferStream(stream_id_list);
		StopStream(stream_id_list);
		RelayStream(stream_id_list, old_host_list, new_host_list);
		return "Success";
	}

	@Override
	public String RelayStream(List<Integer> stream_id_list, List<Integer> old_host_list, List<Integer> new_host_list) {
		RemoveNextHop(stream_id_list, old_host_list);
		AddNextHop(stream_id_list, new_host_list);
		return "Success";
	}

	@Override
	public String RemoveNextHop(List<Integer> stream_id_list, List<Integer> host_list) {
		for (int stream_id : stream_id_list) {
			for (int i = 0; i < streamIdToNodeIds.get(stream_id).size(); i++) {
				for (int host : host_list) {
					if (streamIdToNodeIds.get(stream_id).get(i) == host) {
						streamIdToNodeIds.get(stream_id).remove(i);
						break;
					}
				}
			}
		}

		return "Success";
	}

	@Override
	public String AddSourceNodes(int query_id, List<Integer> stream_id_list, List<Integer> node_id_list) {
		return null;
	}

	@Override
	public String SetAsPotentialHost(List<Integer> stream_id_list) {
		return null;
	}

	@Override
	public String WaitForStoppedStreams(List<Integer> node_id_list, List<Integer> stream_id_list) {
		/*for (int node_id : node_id_list) {
			while (!stream_id_list.isEmpty()) {
				List<Integer> stoppedStreams = nodeIdToStoppedStreams.getOrDefault(node_id, new ArrayList<>());
				for (int i = stream_id_list.size() - 1; i >= 0; i--) {
					int stream_id_to_stop = stream_id_list.get(i);
					for (int stopped_stream_id : stoppedStreams) {
						if (stream_id_to_stop == stopped_stream_id) {
							// Remove from stream_id_list
							stream_id_list.remove(i);
						}
					}
				}
			}
		}*/
		return "Success";
	}

	@Override
	public void LockExecution() {

	}

	@Override
	public void UnlockExecution() {

	}

	public String Configure() {
		for (Map<String, Object> schema : allSchemas.values()) {
			if ((boolean) schema.getOrDefault("intermediary-stream", false)) {
				continue;
			}
			AddKafkaConsumer(schema);
		}
		return "Success";
	}

	public static void main(String[] args) {
		boolean continue_running = true;
		while (continue_running) {
			FlinkExperimentFramework experimentAPI = new FlinkExperimentFramework();
			SpeComm speComm = new SpeComm(args, experimentAPI, experimentAPI);
			experimentAPI.setSpeComm(speComm);
			experimentAPI.setNodeId(speComm.GetNodeId());
			experimentAPI.SetTraceOutputFolder(speComm.GetTraceOutputFolder());
			speComm.AcceptTasks();
		}
	}

	@Override
	public void HandleSpeSpecificTask(Map<String, Object> task) {
		String cmd = (String) task.get("task");
		switch (cmd) {
			case "loadQueryState": {
				List<Object> args = (List<Object>) task.get("arguments");
				byte[] snapshot = (byte[]) args.get(0);
				Map<String, Object> query = (Map<String, Object>) args.get(1);
				String savepointFileName = (String) args.get(2);
				Map<Integer, List<Integer>> stream_ids_to_node_ids = (Map<Integer, List<Integer>>) args.get(3);
				LoadQueryState(snapshot, query, savepointFileName, stream_ids_to_node_ids);
				break;
			} default:
				throw new RuntimeException("Invalid task from mediator: " + cmd);
		}
	}
}
