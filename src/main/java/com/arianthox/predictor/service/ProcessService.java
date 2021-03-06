package com.arianthox.predictor.service;

import akka.Done;
import akka.NotUsed;
import akka.actor.typed.ActorSystem;
import akka.actor.typed.javadsl.Behaviors;
import akka.stream.ActorAttributes;
import akka.stream.javadsl.Sink;
import akka.stream.javadsl.Source;
import com.arianthox.predictor.commons.adapter.KafkaConsumer;
import com.arianthox.predictor.commons.adapter.KafkaProducer;
import com.arianthox.predictor.commons.hhm.dsp.NFourierTransform;
import com.arianthox.predictor.commons.model.DrawVO;
import com.arianthox.predictor.commons.model.TopicID;
import com.arianthox.predictor.commons.utils.CommonUtil;
import com.google.gson.Gson;
import lombok.extern.java.Log;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.stream.Collectors;

@Service
@Log
public class ProcessService {

    private final KafkaConsumer kafkaConsumer;
    private final KafkaProducer kafkaProducer;
    private final MatcherService matcherService;
    private final transient Gson gson;
    private final transient ActorSystem system;

    public ProcessService(KafkaConsumer kafkaConsumer, KafkaProducer kafkaProducer, Gson gson, MatcherService matcherService) {
        this.kafkaConsumer = kafkaConsumer;
        this.kafkaProducer = kafkaProducer;
        this.gson = gson;
        this.system = ActorSystem.create(Behaviors.empty(), "matcher-system");
        this.matcherService = matcherService;
    }

    @PostConstruct
    private void init() {

        kafkaConsumer.consume(TopicID.DRAWS, record -> {

            final Source<DrawVO, NotUsed> flow = Source.single(record)
                    .map(param -> gson.fromJson(param.value(), DrawVO.class))
                    .withAttributes(ActorAttributes.withSupervisionStrategy(CommonUtil.decider));

            final Sink<DrawVO, CompletionStage<Done>> sink = Sink.foreach(draw -> {
                        int[] values = draw.getN().stream().mapToInt(Integer::intValue).toArray();
                        HashMap<String, Double> result = matcherService.matchKey(values);
                        draw.setMatch(result);
                        Map.Entry<List<Double>, List<Double>> fourier = NFourierTransform.computeFFT(draw.getN().stream().mapToDouble(Double::valueOf).boxed().collect(Collectors.toList()));
                        draw.setFourier(fourier);
                        if (result.entrySet().stream().findFirst().get().getValue() <= 1 || result.entrySet().stream().count() > 2) {
                            kafkaProducer.send("results", draw, done -> {
                                //log.info("Result:" + draw.toString() + " - " + Collections.singletonList(result).toString());
                            });
                        } else {
                            //log.info("Result:" + draw.toString() + " - " + Collections.singletonList(result).toString());
                        }
                    }

            );
            return flow.runWith(sink, system);

        });

    }
}
