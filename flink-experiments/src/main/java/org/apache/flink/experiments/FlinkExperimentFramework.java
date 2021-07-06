package org.apache.flink.experiments;

import no.uio.ifi.ExperimentAPI;
import no.uio.ifi.SpeTaskHandler;
import no.uio.ifi.SpeSpecificAPI;
import no.uio.ifi.TracingFramework;
import no.uio.ifi.zip.UnzipFile;
import no.uio.ifi.zip.ZipDirectory;
import org.apache.commons.io.FileUtils;
import org.apache.commons.math3.stat.regression.SimpleRegression;
import org.apache.flink.api.common.JobID;
import org.apache.flink.api.common.functions.FlatMapFunction;
import org.apache.flink.api.common.functions.MapFunction;
import org.apache.flink.api.common.serialization.TypeInformationSerializationSchema;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.common.typeinfo.Types;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.api.java.tuple.Tuple3;
import org.apache.flink.api.java.typeutils.RowTypeInfo;
import org.apache.flink.contrib.streaming.state.RocksDBStateBackend;
import org.apache.flink.runtime.checkpoint.CheckpointCoordinator;
import org.apache.flink.runtime.jobgraph.JobStatus;
import org.apache.flink.runtime.jobgraph.SavepointRestoreSettings;
import org.apache.flink.runtime.state.filesystem.FsStateBackend;
import org.apache.flink.streaming.api.CheckpointingMode;
import org.apache.flink.streaming.api.TimeCharacteristic;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.CheckpointConfig;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.sink.SinkFunction;
import org.apache.flink.streaming.api.functions.source.SourceFunction;
import org.apache.flink.streaming.connectors.kafka.FlinkKafkaConsumer;
import org.apache.flink.streaming.connectors.kafka.FlinkKafkaProducer;
import org.apache.flink.table.api.EnvironmentSettings;
import org.apache.flink.table.api.Table;
import org.apache.flink.table.api.java.StreamTableEnvironment;
import org.apache.flink.types.Row;
import org.apache.flink.util.Collector;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.*;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static java.lang.Math.abs;

@SuppressWarnings("unchecked")
public class FlinkExperimentFramework implements ExperimentAPI, SpeSpecificAPI, Serializable {
	private static final Logger LOG = LoggerFactory.getLogger(FlinkExperimentFramework.class);
	static long timeLastRecvdTuple = 0;
	int batch_size;
	int pktsPublished;
	int interval_wait;
	final int TIMELASTRECEIVEDTHRESHOLD = 1000;  // ms
	boolean useRowtime = false;
	String trace_output_folder;
	StreamExecutionEnvironment env;
	StreamTableEnvironment tableEnv;
	EnvironmentSettings fsSettings = EnvironmentSettings.newInstance().useOldPlanner().inStreamingMode().build();
	Map<Integer, ArrayList<Row> > streamToTuples = new HashMap<>();
	Map<Integer, ArrayList<Map<String, Object>> > outputStreamIdToFetchQueries = new HashMap<>();
	Map<Integer, ArrayList<Map<String, Object>> > outputStreamIdToUpdateQueries = new HashMap<>();
	Map<Integer, Map<Integer, List<Integer>>> queryIdToStreamIdToNodeIds = new HashMap<>();
	List<Map<String, Object>> fetchQueries = new ArrayList<>();
	ArrayList<Map<String, Object>> queries = new ArrayList<>();
	Map<Integer, Map<String, Object>> allSchemas = new HashMap<>();
	Map<Integer, RowTypeInfo> streamIdToTypeInfo = new HashMap<>();
	static Map<Integer, TypeInformationSerializationSchema<Row>> streamIdToSerializationSchema = new HashMap<>();
	Map<String, Integer> streamNameToId = new HashMap<>();
	Map<Integer, String> streamIdToName = new HashMap<>();
	Map<Integer, Map<String, Object>> nodeIdToIpAndPort = new HashMap<>();
	static Map<Integer, List<Integer>> streamIdToNodeIds = new HashMap<>();
	Map<Integer, DataStream<Row> > streamIdToDataStream = new HashMap<>();
	Map<Integer, List<Map<String, Object>>> datasetIdToTuples = new HashMap<>();
	Map<Integer, Boolean> printDataStream = new HashMap<>();
	Map<Integer, Map<String, Object>> queryIdToMapQuery = new HashMap<>();
	Map<Integer, Integer> nodeIdToTuplesPerSecondLimit = new HashMap<>();
	Map<Integer, Long> nodeIdToTupleInterval = new HashMap<>();
	Map<Integer, List<Long>> nodeIdToTimestampsTuplesSent = new HashMap<>();
	final Map<Integer, Map<Long, Long>> nodeIdToMillisecondToForwarded = new HashMap<>();
	Map<Integer, SimpleRegression> nodeIdToRegressionModels = new HashMap<>();
	Map<Integer, List<Long>> nodeIdToTimestampsTuplesDropped = new HashMap<>();
	Map<Integer, List<Tuple3<Long, Double, Long>>> send_schedule = new HashMap<>();
	static TracingFramework tf = new TracingFramework();
	Producer<String, byte[]> producer;
	Map<Integer, SourceFunction<Row>> envSourceFunctions = new HashMap<>();
	List<FlinkKafkaConsumer<Row>> consumers = new ArrayList<>();
	long timestampForKafkaConsumer = 0;
	long millisecond100Offset = 0;
	FsStateBackend fsStateBackend;

	SpeTaskHandler speComm;

	static List<Tuple2<Integer, Row>> incomingTupleBuffer = new ArrayList<>();
	static List<Tuple2<Integer, Row>> outgoingTupleBuffer = new ArrayList<>();
	static Map<Integer, Boolean> streamIdActive = new HashMap<>();
	static Map<Integer, Boolean> streamIdBuffer = new HashMap<>();

	long receivedTuples = 0;

	List<FlinkKafkaProducer<Row> > sinkFunctions = new ArrayList<>();
	Map<Integer, SinkFunction<Row> > regularSinkFunctions = new HashMap<>();
	Map<String, FlinkKafkaProducer<Row> > querySinkFunctions = new HashMap<>();
	//List<SourceFunction<Row> > sourceFunctions = new ArrayList<>();
	Thread threadRunningEnvironment;
	public static int nodeId;
	Properties props;
	static Map<Integer, KafkaProducer> nodeIdToKafkaProducer = new HashMap<>();
	Map<Integer, Properties> nodeIdToProperties = new HashMap<>();
	List<Tuple2<Integer, Row>> all_tuples = new ArrayList<>();

	ServerSocket state_transfer_server = null;

	FlinkExperimentFramework() {
		props = new Properties();
		props.put("bootstrap.servers", "localhost:9092");
		props.put("group.id", "Group 1");
		props.put("auto.offset.reset", "latest");
		props.put("acks", "all");
		props.put("enable.auto.commit", "true");
		props.put("retries", 0);
		props.put("batch.size", 16384);
		props.put("linger.ms", 0);
		props.put("buffer.memory", 33554432);
		props.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
		props.put("value.serializer", "org.apache.kafka.common.serialization.ByteArraySerializer");
	}

	public void setupStateTransferServer(int state_transfer_port) {
		try {
			state_transfer_server = new ServerSocket(state_transfer_port);
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	public void setSpeTaskHandler(SpeTaskHandler speComm) {
		this.speComm = speComm;
	}

	public void setNodeId(int nodeId) {
		FlinkExperimentFramework.nodeId = nodeId;
		props.put("client.id", Integer.toString(nodeId));
		producer = new KafkaProducer<>(props);
		CheckpointCoordinator.nodeId = nodeId;
		new File("/tmp/expose-flink-" + nodeId + "-migration-in-progress").mkdir();
		new File("/tmp/expose-flink-" + nodeId + "-checkpoints-done").mkdir();
		new File("/tmp/expose-flink-" + nodeId + "-waiting-for-final-checkpoint").mkdir();
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
	public String SetTuplesPerSecondLimit(int tuples_per_second, List<Integer> node_list) {
		long ns_between_tuples = (int) ((1.0/tuples_per_second) * 1000000000);
		for (int node_id : node_list) {
			this.nodeIdToTuplesPerSecondLimit.put(node_id, tuples_per_second);
			this.nodeIdToTupleInterval.put(node_id, ns_between_tuples);
			System.out.println("Node tuple interval: " + ns_between_tuples);
		}
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
				int spe_coordinator_port = (int) newNodeIdToIpAndPort.get(nodeId).get("spe-coordinator-port");
				try {
					this.speComm.ConnectToSpeCoordinator(nodeId, ip, spe_coordinator_port);
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
						FlinkKafkaProducer<Row> p = new FlinkKafkaProducer<>(topic, streamIdToSerializationSchema.get(streamId), nodeIdToProperties.get(otherNodeId));
						sinkFunctions.add(p);
						ds.addSink(p);
					}
					break;
				}
			}*/
		}
		for (int nodeId : nodeIds) {
			this.nodeIdToTimestampsTuplesSent.put(nodeId, new ArrayList<>());
			this.nodeIdToMillisecondToForwarded.put(nodeId, new HashMap<>());
			this.nodeIdToTimestampsTuplesDropped.put(nodeId, new ArrayList<>());
			this.nodeIdToRegressionModels.put(nodeId, new SimpleRegression());
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
		//System.out.println("Subscribing to kafka topic " + topic);
		FlinkKafkaConsumer<Row> consumer = new FlinkKafkaConsumer<>(topic, streamIdToSerializationSchema.get(stream_id), this.props);
		if (timestampForKafkaConsumer == 0) {
			consumer.setStartFromTimestamp(System.currentTimeMillis());
		} else {
			consumer.setStartFromTimestamp(timestampForKafkaConsumer);
		}
		DataStream<Row> ds = env.addSource(consumer).uid("stream-" + stream_id).returns(streamIdToTypeInfo.get(stream_id));
		SinkFunction<Row> sf = new SinkFunction<Row>() {
			private final Logger LOG = LoggerFactory.getLogger(SinkFunction.class);
			@Override
			public void invoke(Row value) {
				long newTime = System.currentTimeMillis();
				++cnt[0];
				//System.out.println("Received row: " + value);
				//System.out.println("Received tuple " + cnt[0] + ": " + value);
				// We only log once every maximum one second, to avoid too many tracepoints
				if (newTime - timeLastRecvdTuple > TIMELASTRECEIVEDTHRESHOLD) {
					LOG.info("Received tuple {}", cnt[0]);
					System.out.println("Received tuple " + cnt[0] + ": " + value + " at " + System.currentTimeMillis());
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
				//System.out.println("Received tuple");
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

			datasetIdToTuples.put(ds_id, raw_tuples);
			tuples = raw_tuples;
		}

		for (int i = 0; i < iterations; i++) {
			for (Map<String, Object> tuple : tuples) {
				AddTuples(tuple, 1);
			}
			ProcessTuples(tuples.size(), true);
		}
		return "Success";
	}

	public void AddToSendSchedule(int node_id, long downtime, Double tuples_per_second, long current_on, int step) {
		long next_start = System.currentTimeMillis() + downtime;
		Long next_stop = next_start + current_on + step;
		Tuple3<Long, Double, Long> next_schedule = new Tuple3<>(next_start, tuples_per_second, next_stop);
		send_schedule.put(node_id, new ArrayList<>());
		send_schedule.get(node_id).add(next_schedule);
	}

	/**
	 * Method sends tuples with tuples_per_second
	 * @param desired_tuples_per_second
	 * @param downtime
	 * @param min
	 * @param max
	 * @param step
	 */
	public void SendTuplesVariableOnOff(int desired_tuples_per_second, int downtime, int min, int max, int step) {
		long desired_ns_between_tuples = (int) ((1.0/desired_tuples_per_second) * 1000000000);
		//System.out.println("SendTuplesVariableOnOff: Node " + to_node + ", desired ns tuple interval: " + desired_ns_between_tuples + ", actual ns tuple interval: " + this.nodeIdToTupleInterval.get(to_node));
		//long actual_ns_between_tuples = min(desired_ns_between_tuples, this.nodeIdToTupleInterval.get(to_node));
		Random r = new Random();

		if (all_tuples.isEmpty()) {
			System.out.println("No tuples to process");
		}
		long last_sent = 0;

		Map<Integer, Double> node_to_actual_tuples_per_second = new HashMap<>();
		Map<Integer, Double> node_to_drop_probability = new HashMap<>();

		for (int to_node : this.nodeIdToTuplesPerSecondLimit.keySet()) {
			double max_tuples_per_second = this.nodeIdToTuplesPerSecondLimit.get(to_node);
			double actual_tuples_per_second = Math.min(desired_tuples_per_second, max_tuples_per_second);

			double drop_probability = 100 * (1 - actual_tuples_per_second/desired_tuples_per_second);
			node_to_actual_tuples_per_second.put(to_node, actual_tuples_per_second);
			node_to_drop_probability.put(to_node, drop_probability);
		}

		int current_on = min;
		for (int to_node : node_to_actual_tuples_per_second.keySet()) {
			AddToSendSchedule(to_node, downtime, node_to_actual_tuples_per_second.get(to_node), current_on, step);
		}

		// TODO: tuples_per_second is actually desired tuples per second, and might be higher than what
		// nodeIdToTuplesPerSecondLimit allows
		// Therefore, we must change tuples_per_second if it's too high
		// The problem is that we don't talk about node IDs before we send the tuples, which happens in the
		// next step
		// What we might need is for the next step to wait a certain amount of time after having sent a tuple,
		// so as to make up for the tuple rate.
		// Problem is that we require a lot of assumptions that way
		for (; current_on < max; current_on += step) {
			double desired_total_packets = (desired_tuples_per_second * (current_on/1000.0));

			/*System.out.println("Sending " + actual_total_packets + " over " + current_on + " milliseconds");
			if (actual_tuples_per_second == desired_tuples_per_second) {
				System.out.println("We are hitting the target of how many tuples we want to forward");
			} else {
				System.out.println("Actual tuples this round is " + actual_total_packets + ", versus desired tuples is " + desired_total_packets);
				System.out.println("We attempt to send " + desired_total_packets + ", but have a probability of " + drop_probability + "% of dropping tuples");
			}*/

			// Now, we need to send total_packets to each node that will be a recipient
			// Problem is, we don't know who will be the recipient until we look at the stream IDs
			// I don't want thread synchronization, because that messes things up royally
			// I should really add to which node I want to send the dataset
			//
			for (int curPktsPublished = 1; curPktsPublished <= desired_total_packets; curPktsPublished++) {
				Tuple2<Integer, Row> tuple = all_tuples.get(curPktsPublished % all_tuples.size());
				//Tuple3<byte[], Attribute.Type[], String> t = allPackets.get(curPktsPublished % allPackets.size());
				++pktsPublished;
				if (pktsPublished % 10000 == 0) {
					System.out.println("SendTuples: " + pktsPublished + " tuples");
				}
				long current_time = System.nanoTime();
				if (desired_ns_between_tuples != 0) {
					long time_diff = current_time - last_sent;
					while (time_diff < desired_ns_between_tuples) {
						current_time = System.nanoTime();
						time_diff = current_time - last_sent;
					}
				}

				//System.out.println("random_number: " + random_number + ", drop_probability: " + drop_probability + ", dropped: " + tuple_is_dropped);

				// TODO: Replace to_node here
				/*long current_time_100ms = System.currentTimeMillis() / 100;
				if (millisecond100Offset == 0) {
					millisecond100Offset = current_time_100ms;
					this.nodeIdToMillisecondToForwarded.put(to_node, new HashMap<>());
				}
				long current_time_100ms_norm = current_time_100ms - millisecond100Offset;

				synchronized (this.nodeIdToMillisecondToForwarded) {
					if (current_time_100ms_norm > 10000 || current_time_100ms_norm < 0) {
						System.out.println("current_time_100ms_norm " + current_time_100ms_norm + " is weird");
					}
					this.nodeIdToMillisecondToForwarded.get(to_node).computeIfAbsent(current_time_100ms_norm, k -> 0L);
					this.nodeIdToMillisecondToForwarded.get(to_node).put(current_time_100ms_norm, this.nodeIdToMillisecondToForwarded.get(to_node).get(current_time_100ms_norm) + 1);
				}
				if (tuple_is_dropped) {
					synchronized (this.nodeIdToTimestampsTuplesDropped.get(to_node)) {
						this.nodeIdToTimestampsTuplesDropped.get(to_node).add(0, current_time);
					}
					continue;
				} else {
					synchronized (this.nodeIdToTimestampsTuplesSent.get(to_node)) {
						this.nodeIdToTimestampsTuplesSent.get(to_node).add(0, current_time);
					}
				}*/

				int stream_id = tuple.f0;
				Row row = tuple.f1;
				TypeInformationSerializationSchema<Row> serializationSchema = streamIdToSerializationSchema.get(stream_id);
				String stream_name = (String) allSchemas.get(stream_id).get("name");
				for (int to_node : streamIdToNodeIds.get(stream_id)) {
					String topicName = stream_name + "-" + to_node;
					if (++tupleCnt % 100000 == 0) {
						System.out.println( System.nanoTime() + ": Sending tuple " + (++tupleCnt) + " to node " + to_node + " with topic " + topicName);
					}
					int random_number = abs(r.nextInt() % 101);
					double drop_probability = node_to_drop_probability.get(to_node);
					boolean tuple_is_dropped = random_number <= drop_probability;
					if (tuple_is_dropped) {
						synchronized (this.nodeIdToTimestampsTuplesDropped.get(to_node)) {
							this.nodeIdToTimestampsTuplesDropped.get(to_node).add(0, current_time);
						}
						continue;
					} else {
						synchronized (this.nodeIdToTimestampsTuplesSent.get(to_node)) {
							this.nodeIdToTimestampsTuplesSent.get(to_node).add(0, current_time);
						}
					}
					nodeIdToKafkaProducer.get(to_node).send(new ProducerRecord<>(topicName, serializationSchema.serialize(row)));
				}
				last_sent = System.nanoTime();
			}
			// Do we have another iteration?
			for (int to_node : node_to_actual_tuples_per_second.keySet()) {
				AddToSendSchedule(to_node, downtime, node_to_actual_tuples_per_second.get(to_node), current_on, step);
			}

			if (downtime > 0) {
				System.out.println("Stopping for " + downtime + " milliseconds");
				try {
					Thread.sleep(downtime);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			}
		}
		pktsPublished = 0;
	}

	@Override
	public String SendDsAsVariableOnOffStream(Map<String, Object> ds, int desired_tuples_per_second, int downtime, int min, int max, int step) {
		//System.out.println("Processing dataset");
		int ds_id = (int) ds.get("id");
		List<Map<String, Object>> tuples = datasetIdToTuples.get(ds_id);
		if (tuples == null) {
			System.out.println("Before reading the dataset");
			Map<String, Object> map = GetMapFromYaml(ds);
			System.out.println("After reading the dataset");
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

			datasetIdToTuples.put(ds_id, raw_tuples);
			tuples = raw_tuples;
		}

		for (Map<String, Object> tuple : tuples) {
			AddTuples(tuple, 1);
		}

		new Thread(() -> SendTuplesVariableOnOff(desired_tuples_per_second, downtime, min, max, step)).start();
		boolean cont = true;
		while (cont);
		return "Success";
	}

	@Override
	public String SendDsAsConstantStream(Map<String, Object> ds, int desired_tuples_per_second) {
		//System.out.println("Processing dataset");
		int ds_id = (int) ds.get("id");
		List<Map<String, Object>> tuples = datasetIdToTuples.get(ds_id);
		if (tuples == null) {
			System.out.println("Before reading the dataset");
			Map<String, Object> map = GetMapFromYaml(ds);
			System.out.println("After reading the dataset");
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

			datasetIdToTuples.put(ds_id, raw_tuples);
			tuples = raw_tuples;
		}

		for (Map<String, Object> tuple : tuples) {
			AddTuples(tuple, 1);
		}

		new Thread(() -> SendTuplesVariableOnOff(desired_tuples_per_second, 0, Integer.MAX_VALUE-1, Integer.MAX_VALUE, 0)).start();
		boolean cont = true;
		while (cont);
		return "Success";
	}

	public static class RandomString {

		/**
		 * Generate a random string.
		 */
		public String nextString() {
			for (int idx = 0; idx < buf.length; ++idx)
				buf[idx] = symbols[random.nextInt(symbols.length)];
			return new String(buf);
		}

		public static final String upper = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";

		public static final String lower = upper.toLowerCase(Locale.ROOT);

		public static final String digits = "0123456789";

		public static final String alphanum = upper + lower + digits;

		private final Random random;

		private final char[] symbols;

		private final char[] buf;

		public RandomString(int length, Random random, String symbols) {
			if (length < 1) throw new IllegalArgumentException();
			if (symbols.length() < 2) throw new IllegalArgumentException();
			this.random = Objects.requireNonNull(random);
			this.symbols = symbols.toCharArray();
			this.buf = new char[length];
		}

		/**
		 * Create an alphanumeric string generator.
		 */
		public RandomString(int length, Random random) {
			this(length, random, alphanum);
		}

		/**
		 * Create an alphanumeric strings from a secure generator.
		 */
		public RandomString(int length) {
			this(length, new SecureRandom());
		}

		/**
		 * Create session identifiers.
		 */
		public RandomString() {
			this(21);
		}

	}

	public String AddTuples(Map<String, Object> tuple, int quantity) {
		int stream_id = (int) tuple.get("stream-id");
		ArrayList<Map<String, Object> > attributes = (ArrayList<Map<String, Object> >) tuple.get("attributes");
		Row new_tuple = new Row(attributes.size());
		for (int i = 0; i < attributes.size(); i++) {
			Map<String, Object> attribute = attributes.get(i);
			new_tuple.setField(i, attribute.get("value"));
		}

		all_tuples.add(new Tuple2<>(stream_id, new_tuple));
		return "Success";
	}

	@Override
	public String AddSchemas(List<Map<String, Object>> schemas) {
		env = StreamExecutionEnvironment.getExecutionEnvironment();
		env.enableCheckpointing(1000, CheckpointingMode.EXACTLY_ONCE);
		env.getCheckpointConfig().enableExternalizedCheckpoints(CheckpointConfig.ExternalizedCheckpointCleanup.RETAIN_ON_CANCELLATION);
		env.getCheckpointConfig().setMinPauseBetweenCheckpoints(1000);
		try {
			RocksDBStateBackend rocksdb = new RocksDBStateBackend("file://" + System.getenv("FLINK_BINARIES") + "/state/node-" + nodeId, CheckpointCoordinator.incrementalCheckpointing);
			//fsStateBackend = (FsStateBackend) rocksdb.getCheckpointBackend();
			env.setStateBackend(rocksdb);
		} catch (IOException e) {
			e.printStackTrace();
			System.exit(1021);
		}
		//env.setStateBackend(new FsStateBackend("file://" + System.getenv("FLINK_BINARIES") + "/state/node-" + nodeId));
		env.getConfig().disableSysoutLogging();
		if (useRowtime) {
			env.setStreamTimeCharacteristic(TimeCharacteristic.EventTime);
		} else {
			env.setStreamTimeCharacteristic(TimeCharacteristic.IngestionTime);
		}
		tableEnv = StreamTableEnvironment.create(env, fsSettings);
		tableEnv.registerFunction("DOLTOEUR", new DolToEur());

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

			streamIdToTypeInfo.put(stream_id, new RowTypeInfo(typeInformations));
			streamIdToSerializationSchema.put(stream_id, new TypeInformationSerializationSchema<>(streamIdToTypeInfo.get(stream_id), env.getConfig()));
		}
		return "Success";
	}

	static long produced = 0;
	RandomString rs4 = new RandomString(4);
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
								.map((MapFunction<Tuple2<Boolean, Row>, Row>) value -> value.f1)
								.uid("query-" + query.get("id") + rs4.nextString());
				class CustomFlatMapFunction implements FlatMapFunction<Row, Object> {
					public final int outputStreamId;
					CustomFlatMapFunction(int outputStreamId) {
						this.outputStreamId = outputStreamId;
					}

					@Override
					public void flatMap(Row row, Collector<Object> out) {
						//System.out.println("Produced tuple from query");
						++produced;
						//System.out.println("Produced tuple " + produced);
						if (streamIdActive.getOrDefault(outputStreamId, true)) {
							TypeInformationSerializationSchema<Row> serializationSchema = streamIdToSerializationSchema.get(outputStreamId);
							for (int otherNodeId : streamIdToNodeIds.get(outputStreamId)) {
								String topic = outputStreamName + "-" + otherNodeId;
								//System.out.println("Forwarding tuples to topic " + topic);
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

	JobID jobID;
	String savepointPath;
	SavepointRestoreSettings savepointRestoreSettings = null;

	public String DoStartRuntimeEnv() {
		if (threadRunningEnvironment != null && threadRunningEnvironment.isAlive()) {
			//throw new RuntimeException("The execution environment is already running. " +
			//	                   	   "Stop it with stopRuntimeEnv before running it again.");
			//System.out.println("The execution environment is already running. " +
			//	"Stop it with stopRuntimeEnv before running it again.");
			return "Environment already running";
		}
		cnt[0] = 0;
		DeployQueries();
		threadRunningEnvironment = new Thread(() -> {
			//System.out.println("Starting environment");
			//KafkaFetcher.timeLastRecvdTuple = 0;
			//KafkaFetcher.receivedTuples = 0;
			timeLastRecvdTuple = 0;
			receivedTuples = 0;
			produced = 0;
			System.out.println("Starting runtime");
			try {
				env.execute();
			} catch (Exception e) {
				if (!interrupted) {
					// This interrupt was not because of us
					e.printStackTrace();
				}
				System.out.println("Stopping the execution environment after having received " + cnt[0] + " tuples and produced " + produced + " tuples");
			}
		});
		interrupted = false;
		threadRunningEnvironment.start();
		return "Success";
	}

	@Override
	public String StartRuntimeEnv() {
		DoStartRuntimeEnv();
		return "Success";
	}

	boolean interrupted = false;
	@Override
	public String StopRuntimeEnv() {
		tf.traceEvent(101);

		interrupted = true;
		threadRunningEnvironment.interrupt();

		env = StreamExecutionEnvironment.getExecutionEnvironment();
		env.enableCheckpointing(1000, CheckpointingMode.EXACTLY_ONCE);
		env.getCheckpointConfig().enableExternalizedCheckpoints(CheckpointConfig.ExternalizedCheckpointCleanup.RETAIN_ON_CANCELLATION);
		env.getCheckpointConfig().setMinPauseBetweenCheckpoints(1000);
		env.getCheckpointConfig().setMaxConcurrentCheckpoints(1);
		try {
			RocksDBStateBackend rocksdb = new RocksDBStateBackend("file://" + System.getenv("FLINK_BINARIES") + "/state/node-" + nodeId, CheckpointCoordinator.incrementalCheckpointing);
			//fsStateBackend = (FsStateBackend) rocksdb.getCheckpointBackend();
			env.setStateBackend(rocksdb);
		} catch (IOException e) {
			e.printStackTrace();
			System.exit(1020);
		}
		//env.setStateBackend(new FsStateBackend("file://" + System.getenv("FLINK_BINARIES") + "/state/node-" + nodeId));
		env.getConfig().disableSysoutLogging();
		if (useRowtime) {
			env.setStreamTimeCharacteristic(TimeCharacteristic.EventTime);
		} else {
			env.setStreamTimeCharacteristic(TimeCharacteristic.IngestionTime);
		}
		tableEnv = StreamTableEnvironment.create(env, fsSettings);
		tableEnv.registerFunction("DOLTOEUR", new DolToEur());
		Configure();

		tf.writeTraceToFile(this.trace_output_folder, this.getClass().getSimpleName());

		while (threadRunningEnvironment.isAlive()) {
                        threadRunningEnvironment.interrupt();
			Thread.yield();
		}
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
	RandomString rs = new RandomString(32);
	public String ProcessTuples(int number_tuples, boolean clear_tuples) {
		System.out.println("Processing " + number_tuples + ", or more correctly: " + all_tuples.size() + " tuples");
		for (int i = 0; i < all_tuples.size(); i++) {
			Tuple2<Integer, Row> tuple = all_tuples.get(i);
			int stream_id = tuple.f0;
			Row row = tuple.f1;
			if (stream_id == 2) {
				row.setField(1, rs.nextString());
			}
			TypeInformationSerializationSchema<Row> serializationSchema = streamIdToSerializationSchema.get(stream_id);
			String stream_name = (String) allSchemas.get(stream_id).get("name");
			for (int otherNodeId : streamIdToNodeIds.getOrDefault(stream_id, new ArrayList<>())) {
				String topicName = stream_name + "-" + otherNodeId;
				//for (Row tuple : streamToTuples.get(stream_id)) {
				if (++tupleCnt % 100000 == 0) {
					System.out.println( System.nanoTime() + ": Sending tuple " + (++tupleCnt) + " to node " + otherNodeId + " with topic " + topicName + " and IP " + nodeIdToIpAndPort.get(otherNodeId).get("ip"));
				}
				nodeIdToKafkaProducer.get(otherNodeId).send(new ProducerRecord<>(topicName, null, null, serializationSchema.serialize(row)));
				//}
			}
		}

		if (clear_tuples) {
			all_tuples.clear();
		}
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
			time_diff = cur_time - timeLastRecvdTuple;
			System.out.println("RetEndOfStream, time_diff: " + time_diff + ", cur-time: " + cur_time + ", timeLastRecvdTuple: " + timeLastRecvdTuple + ", received " + cnt[0] + " tuples");
		} while (time_diff < milliseconds || timeLastRecvdTuple == 0);
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
		} while (receivedTuples < numberTuples);
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
	public String MoveQueryState(int new_host) {
		File lockFile;
		File lockFile2;
		String checkpointDirectory = null;
		long ms_start = System.currentTimeMillis();
		try {
			while (env.getMiniCluster().listJobs().get().size() == 0) {
				Thread.yield();
			}
			jobID = env.getMiniCluster().listJobs().get().iterator().next().getJobId();

			lockFile = new File("/tmp/expose-flink-" + nodeId + "-migration-in-progress/" + jobID);
			try {
				lockFile.createNewFile();
			} catch (IOException e) {
				e.printStackTrace();
			}

			checkpointDirectory = System.getenv("FLINK_BINARIES") + "/state/node-" + nodeId + "/" + jobID;
			savepointPath = checkpointDirectory;

			while (env.getMiniCluster().getJobStatus(jobID).get() != JobStatus.RUNNING) {
				Thread.yield();
			}

			Thread.sleep(3000);


		} catch (InterruptedException | ExecutionException e) {
			e.printStackTrace();
			System.exit(31);
		}

		//File savepointPathFile = new File(savepointPath);
		/*while (!savepointPathFile.exists()) {
			Thread.yield();
		}*/
		// TODO: zip savepointPath
		/*String zipPath = System.getenv("FLINK_BINARIES") + "/savepoints/created_zipped_savepoints/zippedSavepoint.zip";
		try {
			ZipDirectory.zipDirectory(savepointPath, zipPath);
		} catch (IOException e) {
			e.printStackTrace();
		}*/

		String zipPath = System.getenv("FLINK_BINARIES") + "/savepoints/created_zipped_savepoints/zippedSavepoint.zip";
		try {
			ZipDirectory.zipDirectory(savepointPath, zipPath);
		} catch (IOException e) {
			e.printStackTrace();
		}

		// Read savepointPath and send it as a byte array
		// LoadQueryState() will write the array to file and restore it
		byte[] snapshot = null;
		System.out.println("Moving query state from savepoint " + savepointPath);
		File file = new File(zipPath);
		while (!file.exists()) {
			System.out.println("Waiting for savepoint file " + file.getPath() + " to be created");
			try {
				System.out.println("Waiting for savepoint file (canonical) " + file.getCanonicalPath() + " to be created");
			} catch (IOException e) {
				e.printStackTrace();
			}
			System.out.println("Waiting for savepoint file (absolute) " + file.getAbsolutePath() + " to be created");
		}
		try {
			snapshot = FileUtils.readFileToByteArray(file);
		} catch (IOException e) {
			e.printStackTrace();
		}
		// Deleting first zip
		file.delete();

		final byte[] finalSnapshot = snapshot;
		new Thread(() -> {
			// Begin to set up state transfer connection
			System.out.println("New host: " + new_host);
			String ip = (String) this.nodeIdToIpAndPort.get(new_host).get("ip");
			int new_host_state_transfer_port = (int) this.nodeIdToIpAndPort.get(new_host).get("state-transfer-port");
			Socket clientSocket = null;
			try {
				clientSocket = new Socket(ip, new_host_state_transfer_port);
			} catch (IOException e) {
				e.printStackTrace();
				System.exit(100);
			}
			try {
				dos.set(new DataOutputStream(clientSocket.getOutputStream()));
				dos.get().writeInt(finalSnapshot.length);
				dos.get().write(finalSnapshot);
			} catch (IOException e) {
				e.printStackTrace();
				System.exit(101);
			}
			// Now we have sent the snapshot to the new host
			// The new host will receive in the task how many bytes it must receive on its socket
			sentFirstZip.set(true);
		}).start();

		try {
			Thread.sleep(20000);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
		lockFile2 = new File("/tmp/expose-flink-" + nodeId + "-waiting-for-final-checkpoint/" + jobID);
		try {
			lockFile2.createNewFile();
		} catch (IOException e) {
			e.printStackTrace();
		}

		try {
			Thread.sleep(10000);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
		// TODO: SEND FINAL CHECKPOINT
		// TODO: SEND THE FINAL INCREMENTAL CHECKPOINT
		File newestIncrementalCheckpoint = null;
		int maxCheckpointNumber = 0;
		for (File child : new File(checkpointDirectory).listFiles()) {
			if (child.getName().startsWith("chk-")) {
				int checkpointNumber = Integer.parseInt(child.getName().split("-")[1]);
				if (checkpointNumber > maxCheckpointNumber) {
					maxCheckpointNumber = checkpointNumber;
					newestIncrementalCheckpoint = child;
				}
			}
		}
		assert newestIncrementalCheckpoint != null;

		System.out.println("Loading checkpoint " + newestIncrementalCheckpoint.getPath());
		zipPath = System.getenv("FLINK_BINARIES") + "/savepoints/created_zipped_savepoints/zippedSavepoint.zip";
		try {
			ZipDirectory.zipDirectory(newestIncrementalCheckpoint.getAbsolutePath(), zipPath);
		} catch (IOException e) {
			e.printStackTrace();
		}

		// Read savepointPath and send it as a byte array
		// LoadQueryState() will write the array to file and restore it
		snapshot = null;
		System.out.println("Moving query state from savepoint " + savepointPath);
		String[] path = savepointPath.split("/");
		String parentFolderName = path[path.length-1];
		file = new File(zipPath);
		while (!file.exists()) {
			System.out.println("Waiting for savepoint file " + file.getPath() + " to be created");
			try {
				System.out.println("Waiting for savepoint file (canonical) " + file.getCanonicalPath() + " to be created");
			} catch (IOException e) {
				e.printStackTrace();
			}
			System.out.println("Waiting for savepoint file (absolute) " + file.getAbsolutePath() + " to be created");
		}
		try {
			snapshot = FileUtils.readFileToByteArray(file);
		} catch (IOException e) {
			e.printStackTrace();
		}

		final byte[] finalSnapshot2 = snapshot;
		new Thread(() -> {
			// Begin to set up state transfer connection
			while (!sentFirstZip.get()) {
				Thread.yield();
			}
			System.out.println("New host: " + new_host);
			try {
				System.out.println("Before sending the mutable state");
				dos.get().writeInt(finalSnapshot2.length);
				dos.get().write(finalSnapshot2);
				System.out.println("After sending the mutable state");
			} catch (IOException e) {
				e.printStackTrace();
			}
			// Now we have sent the snapshot to the new host
			// The new host will receive in the task how many bytes it must receive on its socket
		}).start();
		// TODO: END OF SENDING FINAL CHECKPOINT

		Map<String, Object> task = new HashMap<>();
		task.put("task", "loadQueryState");
		List<Object> task_args = new ArrayList<>();
		task_args.add(finalSnapshot.length);
		task_args.add(finalSnapshot2.length);
		task_args.add(queryIdToMapQuery);
		task_args.add(parentFolderName);
		//List<Map<Integer, List<Integer>>> streamIdsToNodeIds = (List<Map<Integer, List<Integer>>>) queryIdToStreamIdToNodeIds.values();
		task_args.add(queryIdToStreamIdToNodeIds);
		task.put("arguments", task_args);
		task.put("node", Collections.singletonList(new_host));
		long ms_stop1 = System.currentTimeMillis();
		System.out.println("Time at sending state: " + System.currentTimeMillis());
		speComm.speNodeComm.SendToSpe(task);
		long ms_stop2 = System.currentTimeMillis();
		System.out.println("Preparing state took " + (ms_stop1-ms_start) + "ms, and in addition to sending it took " + (ms_stop2-ms_start) + "ms");
		return "Success";
	}

	public String LoadQueryState(int snapshot_length, int snapshot2_length, Map<Integer, Map<String, Object>> queryIdToMapQuery, String savepointName, Map<Integer, Map<Integer, List<Integer>>> queryIdToStreamIdToNodeIds) {
		System.out.println("Time at receiving state: " + System.currentTimeMillis());
		System.out.println("Received query state with " + snapshot_length + " bytes");
		long ms_start = System.currentTimeMillis();
		this.queryIdToMapQuery = queryIdToMapQuery;
		this.queryIdToStreamIdToNodeIds = queryIdToStreamIdToNodeIds;
		// Write snapshot to savepoints/received_savepoints/savepointName
		savepointPath = System.getenv("FLINK_BINARIES") + "/savepoints/received_savepoints/" + savepointName;
		String zippedSavepointPath = System.getenv("FLINK_BINARIES") + "/savepoints/received_zipped_savepoints/zippedSnapshot.zip";
		File zip = new File(zippedSavepointPath);
		zip.delete();
		try {
			zip.createNewFile();
		} catch (IOException e) {
			e.printStackTrace();
		}

		Socket client_socket = null;
		try {
			client_socket = state_transfer_server.accept();
		} catch (IOException e) {
			e.printStackTrace();
			System.exit(102);
		}

		byte[] snapshot = new byte[snapshot_length];
		try {
			new DataInputStream(client_socket.getInputStream()).readFully(snapshot);
		} catch (IOException e) {
			e.printStackTrace();
			System.exit(103);
		}

		try {
			FileUtils.writeByteArrayToFile(zip, snapshot);
		} catch (IOException e) {
			e.printStackTrace();
		}
		System.out.println("Received immutable state");

		while (!zip.exists());

		String[] savepointParentPathArray = savepointPath.split("/");
		savepointParentPathArray[savepointParentPathArray.length-1] = "";
		String savepointParentPath = String.join("/", savepointParentPathArray);
		try {
			UnzipFile.unzip(zippedSavepointPath, savepointParentPath);
		} catch (IOException e) {
			e.printStackTrace();
		}

		byte[] snapshot2 = new byte[snapshot2_length];
		try {
			System.out.println("Before reading the mutable state");
			new DataInputStream(client_socket.getInputStream()).readFully(snapshot2);
			System.out.println("After reading the mutable state");
		} catch (IOException e) {
			e.printStackTrace();
			System.exit(103);
		}

		File zip2 = new File(zippedSavepointPath);
		zip2.delete();
		try {
			zip2.createNewFile();
		} catch (IOException e) {
			e.printStackTrace();
		}

		try {
			FileUtils.writeByteArrayToFile(zip2, snapshot2);
		} catch (IOException e) {
			e.printStackTrace();
		}

		System.out.println("Received mutable state");
		while (!zip2.exists());

		// The difference between Zip 1 and 2 is that we place the contents of the second zip
		// within the first unzipped folder
		String[] savepointParentPathArray2 = savepointPath.split("/");
		String savepointParentPath2 = String.join("/", savepointParentPathArray2);
		try {
			UnzipFile.unzip(zippedSavepointPath, savepointParentPath2);
		} catch (IOException e) {
			e.printStackTrace();
		}

		// Now we're supposed to be done with receiving both the snapshot and the incremental checkpoint
		File newestIncrementalCheckpoint = null;
		int maxCheckpointNumber = 0;
		for (File child : new File(savepointPath).listFiles()) {
			if (child.getName().startsWith("chk-")) {
				int checkpointNumber = Integer.parseInt(child.getName().split("-")[1]);
				if (checkpointNumber > maxCheckpointNumber) {
					maxCheckpointNumber = checkpointNumber;
					newestIncrementalCheckpoint = child;
				}
			}
		}
		assert newestIncrementalCheckpoint != null;
		savepointPath = newestIncrementalCheckpoint.getPath();

		System.out.println("Restoring query state from savepoint " + savepointPath);
		// Restore the snapshot
		savepointRestoreSettings = SavepointRestoreSettings.forPath(savepointPath, false);
		for (Map<String, Object> map_query : queryIdToMapQuery.values()) {
			DeployQueries(map_query);
		}
		StopRuntimeEnv();
		env.setSavepointRestoreSettings(savepointRestoreSettings);
		DoStartRuntimeEnv();
		long ms_stop1 = System.currentTimeMillis();
		for (int query_id : this.queryIdToStreamIdToNodeIds.keySet()) {
			ResumeStream(new ArrayList<>(this.queryIdToStreamIdToNodeIds.get(query_id).keySet()));
		}
		long ms_stop2 = System.currentTimeMillis();
		System.out.println("Loading the state took " + (ms_stop1 - ms_start) + " ms, and in addition to resuming the streams took " + (ms_stop2 - ms_start) + " ms");
		return "Success";
	}

	public void watchMigrationFile(String name) throws IOException, InterruptedException {
		WatchService watchService
				= FileSystems.getDefault().newWatchService();

		java.nio.file.Path path = Paths.get("/tmp/expose-flink-" + nodeId + "-" + name);

		path.register(
				watchService,
				StandardWatchEventKinds.ENTRY_CREATE);

		watchService.take();
	}

	AtomicReference<DataOutputStream> dos = new AtomicReference<>();

	AtomicBoolean sentFirstZip = new AtomicBoolean(false);

	boolean staticQueryFirst = true;

	@Override
	public String MoveStaticQueryState(int query_id, int new_host) {
		if (CheckpointCoordinator.incrementalCheckpointing) {
			return DoMoveStaticQueryState(query_id, new_host);
		} else {
			return "Immutable state migration is disabled if incremental checkpointing is disabled";
		}
	}

	public String DoMoveStaticQueryState(int query_id, int new_host) {
		File lockFile;
		String checkpointDirectory = null;
		long ms_start = System.currentTimeMillis();
		try {
			while (env.getMiniCluster().listJobs().get().size() == 0) {
				Thread.yield();
			}
			jobID = env.getMiniCluster().listJobs().get().iterator().next().getJobId();

			lockFile = new File("/tmp/expose-flink-" + nodeId + "-migration-in-progress/" + jobID);
			try {
				lockFile.createNewFile();
			} catch (IOException e) {
				e.printStackTrace();
			}

			checkpointDirectory = System.getenv("FLINK_BINARIES") + "/state/node-" + nodeId + "/" + jobID;
			savepointPath = checkpointDirectory;

			while (env.getMiniCluster().getJobStatus(jobID).get() != JobStatus.RUNNING) {
				Thread.yield();
			}

		} catch (InterruptedException | ExecutionException e) {
			e.printStackTrace();
			System.exit(31);
		}

		// Here we ensure that no checkpoint is currently in progress
		try {
			watchMigrationFile("checkpoints-done");
		} catch (IOException | InterruptedException e) {
			e.printStackTrace();
			System.exit(58);
		}

		String zipPath = System.getenv("FLINK_BINARIES") + "/savepoints/created_zipped_savepoints/zippedSavepoint.zip";
		File checkpointDirectoryFile = new File(checkpointDirectory);

		try {
			while (checkpointDirectoryFile.lastModified() > System.currentTimeMillis() - 10) {
				Thread.yield();
			}
			ZipDirectory.zipDirectory(savepointPath, zipPath);
		} catch (IOException e) {
			e.printStackTrace();
		}

		// Read savepointPath and send it as a byte array
		// LoadQueryState() will write the array to file and restore it
		byte[] snapshot = null;
		System.out.println("Moving query state from savepoint " + savepointPath);
		String[] path = savepointPath.split("/");
		String parentFolderName = path[path.length-1];
		File file = new File(zipPath);
		while (!file.exists()) {
			System.out.println("Waiting for savepoint file " + file.getPath() + " to be created");
			try {
				System.out.println("Waiting for savepoint file (canonical) " + file.getCanonicalPath() + " to be created");
			} catch (IOException e) {
				e.printStackTrace();
			}
			System.out.println("Waiting for savepoint file (absolute) " + file.getAbsolutePath() + " to be created");
		}
		try {
			snapshot = FileUtils.readFileToByteArray(file);
		} catch (IOException e) {
			e.printStackTrace();
		}
		// Deleting first zip
		file.delete();

		final byte[] finalSnapshot = snapshot;
		new Thread(() -> {
			// Begin to set up state transfer connection
			System.out.println("New host: " + new_host);
			String ip = (String) this.nodeIdToIpAndPort.get(new_host).get("ip");
			int new_host_state_transfer_port = (int) this.nodeIdToIpAndPort.get(new_host).get("state-transfer-port");
			Socket clientSocket = null;
			try {
				clientSocket = new Socket(ip, new_host_state_transfer_port);
			} catch (IOException e) {
				e.printStackTrace();
				System.exit(100);
			}
			try {
				dos.set(new DataOutputStream(clientSocket.getOutputStream()));
			} catch (IOException e) {
				e.printStackTrace();
				System.exit(101);
			}
			try {
				System.out.println("First sending savepointPath " + savepointPath + " with length " + savepointPath.length());
				dos.get().writeInt(savepointPath.length());
				dos.get().writeChars(savepointPath);
				System.out.println("Sending immutable state with " + finalSnapshot.length + " bytes");
				dos.get().writeInt(finalSnapshot.length);
				System.out.println("Sent length of immutable state");
				dos.get().write(finalSnapshot);
				System.out.println("Sent immutable state");
			} catch (IOException e) {
				e.printStackTrace();
			}
			// Now we have sent the snapshot to the new host
			// The new host will receive in the task how many bytes it must receive on its socket
			sentFirstZip.set(true);
		}).start();

		Map<String, Object> task = new HashMap<>();
		task.put("task", "loadQueryState");
		List<Object> task_args = new ArrayList<>();
		task_args.add(queryIdToMapQuery);
		task_args.add(parentFolderName);
		//List<Map<Integer, List<Integer>>> streamIdsToNodeIds = (List<Map<Integer, List<Integer>>>) queryIdToStreamIdToNodeIds.values();
		task_args.add(queryIdToStreamIdToNodeIds);
		task.put("arguments", task_args);
		task.put("node", Collections.singletonList(new_host));
		long ms_stop1 = System.currentTimeMillis();
		System.out.println("Time at sending state: " + System.currentTimeMillis());
		new Thread(() -> speComm.speNodeComm.SendToSpe(task)).start();

		// Send SetRestartTimestamp task before serializing the dynamic state
		Map<String, Object> task2 = new HashMap<>();
		task2.put("task", "setRestartTimestamp");
		task2.put("node", Collections.singletonList(new_host));
		speComm.speNodeComm.SendToSpe(task2);
		while (!sentFirstZip.get()) {
			Thread.yield();
		}
		long ms_stop2 = System.currentTimeMillis();
		System.out.println("Preparing state took " + (ms_stop1-ms_start) + "ms, and in addition to sending it took " + (ms_stop2-ms_start) + "ms");
		return "Success";
	}

	@Override
	public String MoveDynamicQueryState(int query_id, int new_host) {
		File lockFile2;
		long ms_start = System.currentTimeMillis();
		String checkpointDirectory = null;
		try {
			while (env.getMiniCluster().listJobs().get().size() == 0) {
				Thread.yield();
			}
			jobID = env.getMiniCluster().listJobs().get().iterator().next().getJobId();

			checkpointDirectory = System.getenv("FLINK_BINARIES") + "/state/node-" + nodeId + "/" + jobID;
			savepointPath = checkpointDirectory;

			while (env.getMiniCluster().getJobStatus(jobID).get() != JobStatus.RUNNING) {
				Thread.yield();
			}
		} catch (InterruptedException | ExecutionException e) {
			e.printStackTrace();
			System.exit(31);
		}

		lockFile2 = new File("/tmp/expose-flink-" + nodeId + "-waiting-for-final-checkpoint/" + jobID);
		try {
			lockFile2.createNewFile();
		} catch (IOException e) {
			e.printStackTrace();
		}

		if (!CheckpointCoordinator.incrementalCheckpointing) {
			System.out.println("Created file " + lockFile2.getAbsolutePath());
			DoMoveStaticQueryState(query_id, new_host);
			return "Success";
		}

		// Now we wait until the checkpoint is done
		System.out.println("Waiting until checkpoint is done");
		try {
			watchMigrationFile("checkpoints-done");
		} catch (IOException | InterruptedException e) {
			e.printStackTrace();
			System.exit(59);
		}
		System.out.println("Checkpoint is done");

		// TODO: SEND FINAL CHECKPOINT
		// TODO: SEND THE FINAL INCREMENTAL CHECKPOINT
		File newestIncrementalCheckpoint = null;
		int maxCheckpointNumber = 0;
		for (File child : new File(checkpointDirectory).listFiles()) {
			if (child.getName().startsWith("chk-")) {
				int checkpointNumber = Integer.parseInt(child.getName().split("-")[1]);
				if (checkpointNumber > maxCheckpointNumber) {
					maxCheckpointNumber = checkpointNumber;
					newestIncrementalCheckpoint = child;
				}
			}
		}
		assert newestIncrementalCheckpoint != null;

		System.out.println("Loading checkpoint " + newestIncrementalCheckpoint.getPath());
		String zipPath = System.getenv("FLINK_BINARIES") + "/savepoints/created_zipped_savepoints/zippedSavepoint.zip";

		//File metafile = new File(newestIncrementalCheckpoint.getAbsolutePath() + "/_metadata");

		try {
			while (newestIncrementalCheckpoint.lastModified() > System.currentTimeMillis() - 10) {
				Thread.yield();
			}
			ZipDirectory.zipDirectory(savepointPath, zipPath);
		} catch (IOException e) {
			e.printStackTrace();
		}
		try {
			ZipDirectory.zipDirectory(newestIncrementalCheckpoint.getAbsolutePath(), zipPath);
		} catch (IOException e) {
			e.printStackTrace();
		}

		// Read savepointPath and send it as a byte array
		// LoadQueryState() will write the array to file and restore it
		byte[] snapshot = null;
		System.out.println("Moving query state from savepoint " + savepointPath);
		File file = new File(zipPath);
		while (!file.exists()) {
			System.out.println("Waiting for savepoint file " + file.getPath() + " to be created");
			try {
				System.out.println("Waiting for savepoint file (canonical) " + file.getCanonicalPath() + " to be created");
			} catch (IOException e) {
				e.printStackTrace();
			}
			System.out.println("Waiting for savepoint file (absolute) " + file.getAbsolutePath() + " to be created");
		}
		try {
			snapshot = FileUtils.readFileToByteArray(file);
		} catch (IOException e) {
			e.printStackTrace();
		}

		byte[] finalSnapshot = snapshot;
		AtomicBoolean sentSecondZip = new AtomicBoolean(false);
		new Thread(() -> {
			// Begin to set up state transfer connection
			while (!sentFirstZip.get()) {
				Thread.yield();
			}
			System.out.println("New host: " + new_host);
			try {
				System.out.println("Sending mutable state with " + finalSnapshot.length + " bytes");
				dos.get().writeInt(finalSnapshot.length);
				System.out.println("Sent length of mutable state");
				dos.get().write(finalSnapshot);
				System.out.println("Sent mutable state");
			} catch (IOException e) {
				e.printStackTrace();
			}
			// Now we have sent the snapshot to the new host
			// The new host will receive in the task how many bytes it must receive on its socket
			sentSecondZip.set(true);
		}).start();
		// TODO: END OF SENDING FINAL CHECKPOINT
		while (!sentSecondZip.get()) {
			Thread.yield();
		}
		long ms_stop = System.currentTimeMillis();
		System.out.println("Sending dynamic state took " + (ms_stop-ms_start) + " ms");
		return "Success";
	}

	public String SetRestartTimestamp() {
		this.timestampForKafkaConsumer = System.currentTimeMillis();
		return "Success";
	}

	public String LoadQueryState(Map<Integer, Map<String, Object>> queryIdToMapQuery, String savepointName, Map<Integer, Map<Integer, List<Integer>>> queryIdToStreamIdToNodeIds) {
		System.out.println("Time at receiving state: " + System.currentTimeMillis());
		long ms_start = System.currentTimeMillis();
		this.queryIdToMapQuery = queryIdToMapQuery;
		this.queryIdToStreamIdToNodeIds = queryIdToStreamIdToNodeIds;
		// Write snapshot to savepoints/received_savepoints/savepointName
		savepointPath = System.getenv("FLINK_BINARIES") + "/savepoints/received_savepoints/" + savepointName;
		String zippedSavepointPath = System.getenv("FLINK_BINARIES") + "/savepoints/received_zipped_savepoints/zippedSnapshot.zip";
		File zip = new File(zippedSavepointPath);
		zip.delete();

		Socket client_socket;
		DataInputStream dis = null;
		int snapshot_length;
		int snapshot2_length;
		String sourceSavepointPath;
		long ms_start_immutable = 0, ms_stop_immutable = 0;
		long ms_start_mutable = 0, ms_stop_mutable = 0;
		try {
			client_socket = state_transfer_server.accept();
			ms_start_immutable = System.currentTimeMillis();
			dis = new DataInputStream(client_socket.getInputStream());
			int sourceSavepointPathLength = dis.readInt();
			//System.out.println("Received sourceSavepointLength " + sourceSavepointPathLength);
			char[] sourceSavepointPathArray = new char[sourceSavepointPathLength];
			for (int i = 0; i < sourceSavepointPathLength; i++) {
				sourceSavepointPathArray[i] = dis.readChar();
			}
			System.out.println("Received sourceSavepointLength " + sourceSavepointPathLength);
			sourceSavepointPath = String.valueOf(sourceSavepointPathArray);
			System.out.println("sourceSavepoint: " + sourceSavepointPath);
			snapshot_length = dis.readInt();
			byte[] snapshot = new byte[snapshot_length];
			System.out.println("Received immutable state with " + snapshot_length + " bytes");
			dis.readFully(snapshot);
			ms_stop_immutable = System.currentTimeMillis();
			FileUtils.writeByteArrayToFile(zip, snapshot);
			while (!zip.exists()) {
				Thread.yield();
			}
		} catch (IOException e) {
			e.printStackTrace();
			System.exit(103);
		}
		long ms_immutable_unzipped = System.currentTimeMillis();
		System.out.println("Received immutable state");

		String[] savepointParentPathArray = savepointPath.split("/");
		savepointParentPathArray[savepointParentPathArray.length - 1] = "";
		String savepointParentPath = String.join("/", savepointParentPathArray);
		try {
			UnzipFile.unzip(zippedSavepointPath, savepointParentPath);
			zip.delete();
		} catch (IOException e) {
			e.printStackTrace();
			System.exit(103);
		}

		if (CheckpointCoordinator.incrementalCheckpointing) {
			try {
				snapshot2_length = dis.readInt();
				ms_start_mutable = System.currentTimeMillis();
				byte[] snapshot2 = new byte[snapshot2_length];
				System.out.println("Received mutable state with " + snapshot2_length + " bytes");
				dis.readFully(snapshot2);
				FileUtils.writeByteArrayToFile(zip, snapshot2);
				ms_stop_mutable = System.currentTimeMillis();
				while (!zip.exists()) {
					Thread.yield();
				}
			} catch (IOException e) {
				e.printStackTrace();
				System.exit(103);
			}

			System.out.println("Received mutable state");
			while (!zip.exists()) {
				try {
					zip.createNewFile();
				} catch (IOException e) {
					e.printStackTrace();
				}
			}

			// The difference between Zip 1 and 2 is that we place the contents of the second zip
			// within the first unzipped folder
			try {
				UnzipFile.unzip(zippedSavepointPath, savepointPath);
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
		long ms_mutable_unzipped = System.currentTimeMillis();

		// Now we're supposed to be done with receiving both the snapshot and the incremental checkpoint
		File newestIncrementalCheckpoint = null;
		int maxCheckpointNumber = 0;
		for (File child : new File(savepointPath).listFiles()) {
			if (child.getName().startsWith("chk-")) {
				int checkpointNumber = Integer.parseInt(child.getName().split("-")[1]);
				if (checkpointNumber > maxCheckpointNumber) {
					maxCheckpointNumber = checkpointNumber;
					newestIncrementalCheckpoint = child;
				}
			}
		}
		assert newestIncrementalCheckpoint != null;
		savepointPath = newestIncrementalCheckpoint.getPath();

		System.out.println("Restoring query state from savepoint " + savepointPath);
		// Restore the snapshot
		savepointRestoreSettings = SavepointRestoreSettings.forPath(savepointPath, false);
		for (Map<String, Object> map_query : queryIdToMapQuery.values()) {
			DeployQueries(map_query);
		}
		StopRuntimeEnv();
		env.setSavepointRestoreSettings(savepointRestoreSettings);
		DoStartRuntimeEnv();
		long ms_stop = System.currentTimeMillis();
		for (int query_id : this.queryIdToStreamIdToNodeIds.keySet()) {
			ResumeStream(new ArrayList<>(this.queryIdToStreamIdToNodeIds.get(query_id).keySet()));
		}
		System.out.println("Receiving the immutable state took " + (ms_stop_immutable - ms_start_immutable) + " ms");
		System.out.println("Unzipping immutable state took " + (ms_immutable_unzipped - ms_stop_immutable) + " ms");

		System.out.println("Time between receiving the immutable state and the start of receiving the mutable state " + (ms_start_mutable - ms_immutable_unzipped) + " ms");

		System.out.println("Receiving the mutable state took " + (ms_stop_mutable - ms_start_mutable) + " ms");
		System.out.println("Unzipping mutable state took " + (ms_mutable_unzipped - ms_stop_mutable) + " ms");

		System.out.println("Loading the checkpoint took " + (ms_stop - ms_mutable_unzipped) + " ms");
		return "Success";
	}

	@Override
	public String ResumeStream(List<Integer> stream_id_list) {
		for (int stream_id : stream_id_list) {
			streamIdActive.put(stream_id, true);
			streamIdBuffer.put(stream_id, false);
		}
		return "Success";

		/*for (Tuple2<Integer, Row> incoming_tuple : incomingTupleBuffer) {
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
		}*/
		//return "Success";
	}

	@Override
	public String StopStream(List<Integer> stream_id_list, int migration_coordinator_node_id) {
		for (int stream_id : stream_id_list) {
			streamIdActive.put(stream_id, false);
		}
		return "Success";
	}

	@Override
	public String BufferStream(List<Integer> stream_id_list) {
		for (int stream_id : stream_id_list) {
			streamIdBuffer.put(stream_id, true);
		}
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
		if (!queryIdToStreamIdToNodeIds.containsKey(query_id)) {
			queryIdToStreamIdToNodeIds.put(query_id, new HashMap<>());
		}

		Map<Integer, List<Integer>> streamIdsToSourceNodeIds = queryIdToStreamIdToNodeIds.get(query_id);
		for (int stream_id : stream_id_list) {
			List<Integer> value = streamIdsToSourceNodeIds.getOrDefault(stream_id, new ArrayList<>());
			value.addAll(node_id_list);
			streamIdsToSourceNodeIds.put(stream_id, value);
		}
		return "Success";
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
	public Map<String, Object> CollectMetrics(long metrics_window_ms) {
		System.out.println("metrics window in ms: " + metrics_window_ms);
		long metrics_window_ns = metrics_window_ms * 1000000;
		double metrics_window_s = metrics_window_ms / 1000.0;
		long current_time = System.nanoTime();
		long current_time_ms = System.currentTimeMillis();
		Map<String, Object> metrics = new HashMap<>();
		Map<Integer, SimpleRegression> nodeIdToRegressionModels = new HashMap<>();
		metrics.put("node", nodeId);
		Map<Integer, Object> sent_metrics = new HashMap<>();
		Map<Integer, Object> dropped_metrics = new HashMap<>();
		metrics.put("actual-sent-packets-per-second", sent_metrics);
		metrics.put("dropped-packets-per-second", dropped_metrics);
		metrics.put("forwarded-tuples-regression-models", nodeIdToRegressionModels);

		Set<Integer> allNextHopNodes = new HashSet<>();
		for (List<Integer> node_ids : streamIdToNodeIds.values()) {
			allNextHopNodes.addAll(node_ids);
		}
		long cutoff_time = current_time - metrics_window_ns;
		long current_time_100ms = (current_time_ms / 100) - this.millisecond100Offset;
		long cutoff_time_100ms = (current_time_ms - metrics_window_ms) / 100 - this.millisecond100Offset;
		for (int next_hop : allNextHopNodes) {
			int sent_pps;
			int sent_cnt = 0;
			synchronized (this.nodeIdToTimestampsTuplesSent.get(next_hop)) {
				for (long timestampSent : this.nodeIdToTimestampsTuplesSent.get(next_hop)) {
					if (timestampSent < cutoff_time) {
						break;
					}
					++sent_cnt;
				}
			}
			sent_pps = (int) (sent_cnt / metrics_window_s);
			sent_metrics.put(next_hop, Integer.toString(sent_pps));
			int dropped_pps;
			int dropped_cnt = 0;

			synchronized (this.nodeIdToTimestampsTuplesDropped.get(next_hop)) {
				for (long timestampDropped : this.nodeIdToTimestampsTuplesDropped.get(next_hop)) {
					if (timestampDropped < cutoff_time) {
						break;
					}
					++dropped_cnt;
				}
			}
			dropped_pps = (int) (dropped_cnt / metrics_window_s);
			dropped_metrics.put(next_hop, Integer.toString(dropped_pps));
			this.nodeIdToTimestampsTuplesSent.get(next_hop);
			this.nodeIdToTimestampsTuplesDropped.get(next_hop);
		}

		for (int node_id : this.nodeIdToMillisecondToForwarded.keySet()) {
			SimpleRegression regression = new SimpleRegression();
			Map<Long, Long> millisecondsToForwarded = this.nodeIdToMillisecondToForwarded.get(node_id);
			for (long i = 0; i <= current_time_100ms; i++) {
				synchronized (this.nodeIdToMillisecondToForwarded.get(node_id)) {
					this.nodeIdToMillisecondToForwarded.get(node_id).computeIfAbsent(i, k -> 0L);
				}
			}
			for (long ms : millisecondsToForwarded.keySet()) {
				if (ms < cutoff_time_100ms) {
					continue;
				}
				long numberForwarded = millisecondsToForwarded.get(ms);
				System.out.println("Number forwarded at " + ms + ": " + numberForwarded + ", cutoff_time_100ms: " + cutoff_time_100ms);
				regression.addData(ms, numberForwarded);
			}
			System.out.println("Regression slope for Node " + node_id + ": " + regression.getSlope());
			nodeIdToRegressionModels.put(node_id, regression);
		}

		return metrics;
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
		this.timestampForKafkaConsumer = 0;
		return "Success";
	}

	public static void main(String[] args) {
		boolean continue_running = true;
		while (continue_running) {
			FlinkExperimentFramework experimentAPI = new FlinkExperimentFramework();
			SpeTaskHandler speComm = new SpeTaskHandler(args, experimentAPI, experimentAPI);
			experimentAPI.setSpeTaskHandler(speComm);
			experimentAPI.setNodeId(speComm.GetNodeId());
			experimentAPI.SetTraceOutputFolder(speComm.GetTraceOutputFolder());
			int state_transfer_port = speComm.GetStateTransferPort();
			if (state_transfer_port != -1) {
				experimentAPI.setupStateTransferServer(state_transfer_port);
			}
			//speComm.AcceptTasks();
			while (true);
		}
	}

	@Override
	public void HandleSpeSpecificTask(Map<String, Object> task) {
		String cmd = (String) task.get("task");
		switch (cmd) {
			case "loadQueryState": {
				List<Object> args = (List<Object>) task.get("arguments");
				Map<Integer, Map<String, Object>> queryIdToMapQuery = (Map<Integer, Map<String, Object>>) args.get(0);
				String savepointFileName = (String) args.get(1);
				Map<Integer, Map<Integer, List<Integer>>> queryIdToStreamIdToNodeIds = (Map<Integer, Map<Integer, List<Integer>>>) args.get(2);
				LoadQueryState(queryIdToMapQuery, savepointFileName, queryIdToStreamIdToNodeIds);
				break;
			} case "setRestartTimestamp": {
				SetRestartTimestamp();
				break;
			} default: {
				throw new RuntimeException("Invalid task from mediator: " + cmd);
			}
		}
	}
}
