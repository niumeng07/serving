package com.tencent.angel.serving.servables.common

import com.tencent.angel.serving.apis.common.ModelSpecProtos.ModelSpec
import com.tencent.angel.serving.apis.prediction.ClassificationProtos.{ClassificationRequest, ClassificationResponse}
import com.tencent.angel.serving.core.{ServableHandle, ServableRequest, ServerCore}
import com.tencent.angel.serving.servables.angel.{RunOptions, SavedModelBundle}
import org.slf4j.{Logger, LoggerFactory}

object ClassificationServiceImpl {

  private val LOG: Logger = LoggerFactory.getLogger(getClass)

  def classify(runOptions: RunOptions, core: ServerCore,
               request: ClassificationRequest, responseBuilder: ClassificationResponse.Builder): Unit = {
    if(!request.hasModelSpec){
      LOG.info("Missing ModelSpec")
      return
    }
    classifyWithModelSpec(runOptions, core, request.getModelSpec, request, responseBuilder)
  }

  def classifyWithModelSpec(runOptions: RunOptions, core: ServerCore, modelSpec: ModelSpec,
                            request: ClassificationRequest, responseBuilder: ClassificationResponse.Builder): Unit = {
    val servableHandle: ServableHandle[SavedModelBundle] = core.servableHandle(ServableRequest.specific(modelSpec.getName, modelSpec.getVersion.getValue))
    Classifier.runClassify(runOptions, servableHandle.servable.metaGraphDef, servableHandle.id.version,
      servableHandle.servable.session, request, responseBuilder)
  }

}
