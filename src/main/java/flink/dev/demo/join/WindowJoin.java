/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package flink.dev.demo.join;

import org.apache.flink.api.common.eventtime.*;
import org.apache.flink.api.common.functions.JoinFunction;
import org.apache.flink.api.common.serialization.SimpleStringEncoder;
import org.apache.flink.api.java.functions.KeySelector;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.api.java.tuple.Tuple3;
import org.apache.flink.api.java.utils.ParameterTool;
import org.apache.flink.configuration.MemorySize;
import org.apache.flink.connector.file.sink.FileSink;
import org.apache.flink.core.fs.Path;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.sink.filesystem.rollingpolicies.DefaultRollingPolicy;
import org.apache.flink.streaming.api.windowing.assigners.TumblingEventTimeWindows;

import java.time.Duration;

/**
 * Example illustrating a windowed stream join between two data streams.
 *
 * <p>The example works on two input streams with pairs (name, grade) and (name, salary)
 * respectively. It joins the streams based on "name" within a configurable window.
 *
 * <p>The example uses a built-in sample data generator that generates the streams of pairs at a
 * configurable rate.
 */
public class WindowJoin {

    // *************************************************************************
    // PROGRAM
    // *************************************************************************

    public static void main(String[] args) throws Exception {
        // parse the parameters
        final ParameterTool params = ParameterTool.fromArgs(args);
        final long windowSize = params.getLong("windowSize", 2000);
        final long rate = params.getLong("rate", 3L);
        final boolean fileOutput = params.has("output");

        System.out.println("Using windowSize=" + windowSize + ", data rate=" + rate);
        System.out.println(
                "To customize example, use: WindowJoin [--windowSize <window-size-in-millis>] [--rate <elements-per-second>]");

        // obtain execution environment, run this example in "ingestion time"
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

        // make parameters available in the web interface
        env.getConfig().setGlobalJobParameters(params);

        // create the data sources for both grades and salaries
        DataStream<Tuple2<String, Integer>> grades =
                env.fromSource(
                                WindowJoinSampleData.getGradeGeneratorSource(rate),
                                IngestionTimeWatermarkStrategy.create(),
                                "Grades Data Generator")
                        .setParallelism(1);

        DataStream<Tuple2<String, Integer>> salaries =
                env.fromSource(
                                WindowJoinSampleData.getSalaryGeneratorSource(rate),
                                IngestionTimeWatermarkStrategy.create(),
                                "Grades Data Generator")
                        .setParallelism(1);

        // run the actual window join program
        // for testability, this functionality is in a separate method.
        DataStream<Tuple3<String, Integer, Integer>> joinedStream =
                runWindowJoin(grades, salaries, windowSize);

        if (fileOutput) {
            joinedStream
                    .sinkTo(
                            FileSink.<Tuple3<String, Integer, Integer>>forRowFormat(
                                            new Path(params.get("output")),
                                            new SimpleStringEncoder<>())
                                    .withRollingPolicy(
                                            DefaultRollingPolicy.builder()
                                                    .withMaxPartSize(MemorySize.ofMebiBytes(1))
                                                    .withRolloverInterval(Duration.ofSeconds(10))
                                                    .build())
                                    .build())
                    .name("output");
        } else {
            // print the results with a single thread, rather than in parallel
            joinedStream.print().setParallelism(1);
        }

        // execute program
        env.execute("Windowed Join Example");
    }

    public static DataStream<Tuple3<String, Integer, Integer>> runWindowJoin(
            DataStream<Tuple2<String, Integer>> grades,
            DataStream<Tuple2<String, Integer>> salaries,
            long windowSize) {

        return grades.join(salaries)
                .where(new NameKeySelector())
                .equalTo(new NameKeySelector())
                .window(TumblingEventTimeWindows.of(Duration.ofMillis(windowSize)))
                .apply(
                        new JoinFunction<
                                Tuple2<String, Integer>,
                                Tuple2<String, Integer>,
                                Tuple3<String, Integer, Integer>>() {

                            @Override
                            public Tuple3<String, Integer, Integer> join(
                                    Tuple2<String, Integer> first, Tuple2<String, Integer> second) {
                                return new Tuple3<String, Integer, Integer>(
                                        first.f0, first.f1, second.f1);
                            }
                        });
    }

    private static class NameKeySelector implements KeySelector<Tuple2<String, Integer>, String> {
        @Override
        public String getKey(Tuple2<String, Integer> value) {
            return value.f0;
        }
    }

    /**
     * This {@link WatermarkStrategy} assigns the current system time as the event-time timestamp.
     * In a real use case you should use proper timestamps and an appropriate {@link
     * WatermarkStrategy}.
     */
    private static class IngestionTimeWatermarkStrategy<T> implements WatermarkStrategy<T> {

        private IngestionTimeWatermarkStrategy() {}

        public static <T> IngestionTimeWatermarkStrategy<T> create() {
            return new IngestionTimeWatermarkStrategy<>();
        }

        @Override
        public WatermarkGenerator<T> createWatermarkGenerator(
                WatermarkGeneratorSupplier.Context context) {
            return new AscendingTimestampsWatermarks<>();
        }

        @Override
        public TimestampAssigner<T> createTimestampAssigner(
                TimestampAssignerSupplier.Context context) {
            return (event, timestamp) -> System.currentTimeMillis();
        }
    }
}
