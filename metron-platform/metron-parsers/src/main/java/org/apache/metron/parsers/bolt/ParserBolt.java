/**
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
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.metron.parsers.bolt;

import backtype.storm.task.OutputCollector;
import backtype.storm.task.TopologyContext;
import backtype.storm.topology.OutputFieldsDeclarer;
import backtype.storm.tuple.Fields;
import backtype.storm.tuple.Tuple;
import backtype.storm.tuple.Values;
import org.apache.metron.common.Constants;
import org.apache.metron.common.bolt.ConfiguredParserBolt;
import org.apache.metron.common.configuration.FieldValidator;
import org.apache.metron.common.configuration.SensorParserConfig;
import org.apache.metron.common.dsl.Context;
import org.apache.metron.common.dsl.FunctionResolver;
import org.apache.metron.common.dsl.StellarFunctions;
import org.apache.metron.parsers.filters.Filters;
import org.apache.metron.common.configuration.FieldTransformer;
import org.apache.metron.parsers.filters.GenericMessageFilter;
import org.apache.metron.common.utils.ErrorUtils;
import org.apache.metron.parsers.interfaces.MessageFilter;
import org.apache.metron.parsers.interfaces.MessageParser;
import org.json.simple.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.util.*;

public class ParserBolt extends ConfiguredParserBolt implements Serializable {

  private static final Logger LOG = LoggerFactory.getLogger(ParserBolt.class);
  private OutputCollector collector;
  private MessageParser<JSONObject> parser;
  private MessageFilter<JSONObject> filter = new GenericMessageFilter();
  private WriterHandler writer;
  private org.apache.metron.common.dsl.Context stellarContext;
  public ParserBolt( String zookeeperUrl
                   , String sensorType
                   , MessageParser<JSONObject> parser
                   , WriterHandler writer
  )
  {
    super(zookeeperUrl, sensorType);
    this.writer = writer;
    this.parser = parser;
  }


  public ParserBolt withMessageFilter(MessageFilter<JSONObject> filter) {
    this.filter = filter;
    return this;
  }

  @SuppressWarnings("unchecked")
  @Override
  public void prepare(Map stormConf, TopologyContext context, OutputCollector collector) {
    super.prepare(stormConf, context, collector);
    this.collector = collector;
    if(getSensorParserConfig() == null) {
      filter = new GenericMessageFilter();
    }
    else if(filter == null) {
      filter = Filters.get(getSensorParserConfig().getFilterClassName()
              , getSensorParserConfig().getParserConfig()
      );
    }
    parser.init();

    writer.init(stormConf, collector, getConfigurations());

    SensorParserConfig config = getSensorParserConfig();
    if(config != null) {
      config.init();
    }
    else {
      throw new IllegalStateException("Unable to retrieve a parser config for " + getSensorType());
    }
    parser.configure(config.getParserConfig());
    initializeStellar();
  }

  protected void initializeStellar() {
    this.stellarContext = new Context.Builder()
                                .with(Context.Capabilities.ZOOKEEPER_CLIENT, () -> client)
                                .build();
    StellarFunctions.FUNCTION_RESOLVER().initializeFunctions(stellarContext);
  }

  @SuppressWarnings("unchecked")
  @Override
  public void execute(Tuple tuple) {
    byte[] originalMessage = tuple.getBinary(0);
    SensorParserConfig sensorParserConfig = getSensorParserConfig();
    try {
      //we want to ack the tuple in the situation where we have are not doing a bulk write
      //otherwise we want to defer to the writerComponent who will ack on bulk commit.
      boolean ackTuple = !writer.handleAck();
      int numWritten = 0;
      if(sensorParserConfig != null) {
        List<FieldValidator> fieldValidations = getConfigurations().getFieldValidations();
        Optional<List<JSONObject>> messages = parser.parseOptional(originalMessage);
        for (JSONObject message : messages.orElse(Collections.emptyList())) {
          if (parser.validate(message) && filter != null && filter.emitTuple(message, stellarContext)) {
            message.put(Constants.SENSOR_TYPE, getSensorType());
            for (FieldTransformer handler : sensorParserConfig.getFieldTransformations()) {
              if (handler != null) {
                handler.transformAndUpdate(message, sensorParserConfig.getParserConfig(), stellarContext);
              }
            }
            numWritten++;
            if(!isGloballyValid(message, fieldValidations)) {
              message.put(Constants.SENSOR_TYPE, getSensorType()+ ".invalid");
              collector.emit(Constants.INVALID_STREAM, new Values(message));
            }
            else {
              writer.write(getSensorType(), tuple, message, getConfigurations());
            }
          }
        }
      }
      //if we are supposed to ack the tuple OR if we've never passed this tuple to the bulk writer
      //(meaning that none of the messages are valid either globally or locally)
      //then we want to handle the ack ourselves.
      if(ackTuple || numWritten == 0) {
        collector.ack(tuple);
      }
    } catch (Throwable ex) {
      ErrorUtils.handleError( collector
                            , ex
                            , Constants.ERROR_STREAM
                            , Optional.of(getSensorType())
                            , Optional.ofNullable(originalMessage)
                            );
      collector.ack(tuple);
    }
  }

  private boolean isGloballyValid(JSONObject input, List<FieldValidator> validators) {
    for(FieldValidator validator : validators) {
      if(!validator.isValid(input, getConfigurations().getGlobalConfig(), stellarContext)) {
        return false;
      }
    }
    return true;
  }

  @Override
  public void declareOutputFields(OutputFieldsDeclarer declarer) {
    declarer.declareStream(Constants.INVALID_STREAM, new Fields("message"));
    declarer.declareStream(Constants.ERROR_STREAM, new Fields("message"));
  }
}
