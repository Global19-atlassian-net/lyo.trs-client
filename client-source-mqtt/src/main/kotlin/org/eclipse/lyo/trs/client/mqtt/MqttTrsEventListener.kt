/*
 * Copyright (c) 2017 Xufei Ning and others
 *
 * All rights reserved. This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 and Eclipse Distribution License v. 1.0 which
 * accompanies this distribution.
 *
 * The Eclipse Public License is available at http://www.eclipse.org/legal/epl-v10.html and the
 * Eclipse Distribution License is available at http://www.eclipse.org/org/documents/edl-v10.php.
 */

package org.eclipse.lyo.trs.client.mqtt

import org.apache.jena.rdf.model.Model
import org.apache.jena.rdf.model.ModelFactory
import org.apache.jena.rdf.model.Resource
import org.apache.jena.rdf.model.ResourceFactory
import org.apache.jena.riot.Lang
import org.apache.jena.riot.RDFDataMgr
import org.eclipse.lyo.core.trs.ChangeEvent
import org.eclipse.lyo.core.trs.Creation
import org.eclipse.lyo.core.trs.Deletion
import org.eclipse.lyo.core.trs.Modification
import org.eclipse.lyo.oslc4j.core.exception.LyoModelException
import org.eclipse.lyo.oslc4j.provider.jena.JenaModelHelper
import org.eclipse.lyo.trs.client.handlers.TrsProviderHandler
import org.eclipse.lyo.trs.client.model.ChangeEventMessageTR
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttCallback
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.concurrent.ScheduledExecutorService

class MqttTrsEventListener(private val providerHandler: TrsProviderHandler,
                           private val executorService: ScheduledExecutorService) : MqttCallback {
    private val log = LoggerFactory.getLogger(MqttTrsEventListener::class.java)

//    @Throws(Exception::class)
    override fun messageArrived(s: String, mqttMessage: MqttMessage) {
        val payload = String(mqttMessage.payload)
        log.info("Message received: $payload")
        if (payload.equals("NEW", ignoreCase = true)) {
            log.warn("Plain 'NEW' ping message received")
            throw IllegalArgumentException(
                    "'NEW' payload is no longer supported; send an RDF graph")
        }
        executorService.submit {
            log.info("Full ChangeEvent received")
            try {
                if (payload.startsWith("<ModelCom")) {
                    throw IllegalArgumentException("Malformed RDF from the serialised Jena Model; use RDFDataMgr")
                } else {
                    val eventMessage = unmarshalChangeEvent(payload)
                    providerHandler.processChangeEvent(eventMessage)
                }
            } catch (e: LyoModelException) {
                log.warn("Error processing event", e)
            }
        }
    }

    override fun deliveryComplete(token: IMqttDeliveryToken) {
        log.trace("deliveryComplete: {}", token)
    }

    override fun connectionLost(throwable: Throwable) {
        log.error("Connection with broker lost", throwable)
    }

//    @Throws(LyoModelException::class)
    private fun unmarshalChangeEvent(payload: String): ChangeEventMessageTR {
        log.debug("MQTT payload: {}", payload)
        var changeEvent: ChangeEvent
        val payloadModel = ModelFactory.createDefaultModel()
        val inputStream = ByteArrayInputStream(payload.toByteArray(StandardCharsets.UTF_8))
        RDFDataMgr.read(payloadModel, inputStream, Lang.JSONLD)

        // FIXME Andrew@2019-07-15: test the patch from Ricardo finally
        try {
            changeEvent = JenaModelHelper.unmarshalSingle(payloadModel, Modification::class.java)
        } catch (e: LyoModelException) {
            try {
                changeEvent = JenaModelHelper.unmarshalSingle(payloadModel, Creation::class.java)
            } catch (e1: LyoModelException) {
                try {
                    changeEvent = JenaModelHelper.unmarshalSingle(payloadModel,
                            Deletion::class.java)
                } catch (e2: LyoModelException) {
                    log.error("Can't unmarshal the payload", e2)
                    throw e2
                }

            }

        }

        val trModel = ModelFactory.createDefaultModel()
        trModel.add(payloadModel)
        removeResource(changeEvent.changed, trModel)

        return ChangeEventMessageTR(changeEvent, trModel)
    }

    private fun removeResource(subject: URI, model: Model) {
        model.removeAll(r(subject), null, null)
    }

    /**
     * Dummy Jena Resource for a URI. Can be to do raw ops on a model.
     */
    private fun r(tResourceUri: URI): Resource {
        return ResourceFactory.createResource(tResourceUri.toString())
    }
}