// Copyright (c) 2018-2020 VMware, Inc. or its affiliates.  All rights reserved.
//
// This software, the RabbitMQ Java client library, is triple-licensed under the
// Mozilla Public License 2.0 ("MPL"), the GNU General Public License version 2
// ("GPL") and the Apache License version 2 ("ASL"). For the MPL, please see
// LICENSE-MPL-RabbitMQ. For the GPL, please see LICENSE-GPL2.  For the ASL,
// please see LICENSE-APACHE2.
//
// This software is distributed on an "AS IS" basis, WITHOUT WARRANTY OF ANY KIND,
// either express or implied. See the LICENSE file for specific language governing
// rights and limitations of this software.
//
// If you have any questions regarding licensing, please contact us at
// info@rabbitmq.com.

package com.rabbitmq.client.impl.recovery;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.ShutdownSignalException;
import com.rabbitmq.utility.Utility;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.Map.Entry;
import java.util.function.BiPredicate;
import static com.rabbitmq.client.impl.recovery.TopologyRecoveryRetryHandlerBuilder.builder;

/**
 * Useful ready-to-use conditions and operations for {@link DefaultRetryHandler}.
 * They're composed and used with the {@link TopologyRecoveryRetryHandlerBuilder}.
 *
 * @see DefaultRetryHandler
 * @see RetryHandler
 * @see TopologyRecoveryRetryHandlerBuilder
 * @since 5.4.0
 */
public abstract class TopologyRecoveryRetryLogic {

    /**
     * Channel has been closed because of a resource that doesn't exist.
     */
    public static final BiPredicate<RecordedEntity, Exception> CHANNEL_CLOSED_NOT_FOUND = (entity, ex) -> {
        if (ex.getCause() instanceof ShutdownSignalException) {
            ShutdownSignalException cause = (ShutdownSignalException) ex.getCause();
            if (cause.getReason() instanceof AMQP.Channel.Close) {
                return ((AMQP.Channel.Close) cause.getReason()).getReplyCode() == 404;
            }
        }
        return false;
    };

    /**
     * Recover a channel.
     */
    public static final DefaultRetryHandler.RetryOperation<Void> RECOVER_CHANNEL = context -> {
        if (!context.entity().getChannel().isOpen()) {
            context.connection().recoverChannel(context.entity().getChannel());
        }
        return null;
    };
    
    /**
     * Recover a queue
     */
    public static final DefaultRetryHandler.RetryOperation<Void> RECOVER_QUEUE = context -> {
        if (context.entity() instanceof RecordedQueue) {
            final RecordedQueue recordedQueue = context.queue();
            AutorecoveringConnection connection = context.connection();
          connection.recoverQueue(recordedQueue.getName(), recordedQueue);
        }
        return null;
    };

    /**
     * Recover the destination queue of a binding.
     */
    public static final DefaultRetryHandler.RetryOperation<Void> RECOVER_BINDING_QUEUE = context -> {
        if (context.entity() instanceof RecordedQueueBinding) {
            RecordedBinding binding = context.binding();
            AutorecoveringConnection connection = context.connection();
            RecordedQueue recordedQueue = connection.getRecordedQueues().get(binding.getDestination());
            if (recordedQueue != null) {
              connection.recoverQueue(recordedQueue.getName(), recordedQueue);
            }
        }
        return null;
    };

    /**
     * Recover a binding.
     */
    public static final DefaultRetryHandler.RetryOperation<Void> RECOVER_BINDING = context -> {
        context.binding().recover();
        return null;
    };
    
    /**
     * Recover earlier bindings that share the same queue as this retry context
     */
    public static final DefaultRetryHandler.RetryOperation<Void> RECOVER_PREVIOUS_QUEUE_BINDINGS = context -> {
        if (context.entity() instanceof RecordedQueueBinding) {
            // recover all bindings for the same queue that were recovered before this current binding
            // need to do this incase some bindings had already been recovered successfully before the queue was deleted & this binding failed
            String queue = context.binding().getDestination();
            for (RecordedBinding recordedBinding : Utility.copy(context.connection().getRecordedBindings())) {
                if (recordedBinding == context.entity()) {
                    // we have gotten to the binding in this context. Since this is an ordered list we can now break
                    // as we know we have recovered all the earlier bindings that may have existed on this queue
                    break;
                } else if (recordedBinding instanceof RecordedQueueBinding && queue.equals(recordedBinding.getDestination())) {
                    recordedBinding.recover();
                }
            }
        }
        return null;
    };

    /**
     * Recover the queue of a consumer.
     */
    public static final DefaultRetryHandler.RetryOperation<Void> RECOVER_CONSUMER_QUEUE = context -> {
        if (context.entity() instanceof RecordedConsumer) {
            RecordedConsumer consumer = context.consumer();
            AutorecoveringConnection connection = context.connection();
            RecordedQueue recordedQueue = connection.getRecordedQueues().get(consumer.getQueue());
            if (recordedQueue != null) {
              connection.recoverQueue(recordedQueue.getName(), recordedQueue);
            }
        }
        return null;
    };

    /**
     * Recover all the bindings of the queue of a consumer.
     */
    public static final DefaultRetryHandler.RetryOperation<Void> RECOVER_CONSUMER_QUEUE_BINDINGS = context -> {
        if (context.entity() instanceof RecordedConsumer) {
            String queue = context.consumer().getQueue();
            for (RecordedBinding recordedBinding : Utility.copy(context.connection().getRecordedBindings())) {
                if (recordedBinding instanceof RecordedQueueBinding && queue.equals(recordedBinding.getDestination())) {
                    recordedBinding.recover();
                }
            }
        }
        return null;
    };

    /**
     * Recover a consumer.
     */
    public static final DefaultRetryHandler.RetryOperation<String> RECOVER_CONSUMER = context -> context.consumer().recover();
    
    /**
     * Recover earlier consumers that share the same channel as this retry context
     */
    public static final DefaultRetryHandler.RetryOperation<String> RECOVER_PREVIOUS_CONSUMERS = context -> {
        if (context.entity() instanceof RecordedConsumer) {
            // recover all consumers for the same channel that were recovered before this current
            // consumer. need to do this incase some consumers had already been recovered
            // successfully on a different queue before this one failed
            final AutorecoveringChannel channel = context.consumer().getChannel();
            for (RecordedConsumer consumer : Utility.copy(context.connection().getRecordedConsumers()).values()) {
                if (consumer == context.entity()) {
                    break;
                } else if (consumer.getChannel() == channel) {
                    final RetryContext retryContext = new RetryContext(consumer, context.exception(), context.connection());
                    RECOVER_CONSUMER_QUEUE.call(retryContext);
                    context.connection().recoverConsumer(consumer.getConsumerTag(), consumer);
                    RECOVER_CONSUMER_QUEUE_BINDINGS.call(retryContext);
                }
            }
            return context.consumer().getConsumerTag();
        }
        return null;
    };
    
    /**
     * Recover earlier auto-delete or exclusive queues that share the same channel as this retry context
     */
    public static final DefaultRetryHandler.RetryOperation<Void> RECOVER_PREVIOUS_AUTO_DELETE_QUEUES = context -> {
        if (context.entity() instanceof RecordedQueue) {
            AutorecoveringConnection connection = context.connection();
            RecordedQueue queue = context.queue();
            // recover all queues for the same channel that had already been recovered successfully before this queue failed.
            // If the previous ones were auto-delete or exclusive, they need recovered again
            for (Entry<String, RecordedQueue> entry : Utility.copy(connection.getRecordedQueues()).entrySet()) {
                if (entry.getValue() == queue) {
                    // we have gotten to the queue in this context. Since this is an ordered map we can now break 
                    // as we know we have recovered all the earlier queues on this channel
                    break;
                } else if (queue.getChannel() == entry.getValue().getChannel() 
                        && (entry.getValue().isAutoDelete() || entry.getValue().isExclusive())) {
                    connection.recoverQueue(entry.getKey(), entry.getValue());
                }
            }
        } else if (context.entity() instanceof RecordedQueueBinding) {
            AutorecoveringConnection connection = context.connection();
            Set<String> queues = new LinkedHashSet<>();
            for (Entry<String, RecordedQueue> entry : Utility.copy(connection.getRecordedQueues()).entrySet()) {
                if (context.entity().getChannel() == entry.getValue().getChannel() 
                        && (entry.getValue().isAutoDelete() || entry.getValue().isExclusive())) {
                    connection.recoverQueue(entry.getKey(), entry.getValue());
                    queues.add(entry.getValue().getName());
                }
            }
            for (final RecordedBinding binding : Utility.copy(connection.getRecordedBindings())) {
                if (binding instanceof RecordedQueueBinding && queues.contains(binding.getDestination())) {
                    binding.recover();
                }
            }
        }
        return null;
    };

    /**
     * Pre-configured {@link TopologyRecoveryRetryHandlerBuilder} that retries recovery of bindings and consumers
     * when their respective queue is not found.
     * 
     * This retry handler can be useful for long recovery processes, whereby auto-delete queues
     * can be deleted between queue recovery and binding/consumer recovery.
     * 
     * Also useful to retry channel-closed 404 errors that may arise with auto-delete queues during a cluster cycle.
     */
    public static final TopologyRecoveryRetryHandlerBuilder RETRY_ON_QUEUE_NOT_FOUND_RETRY_HANDLER = builder()
        .queueRecoveryRetryCondition(CHANNEL_CLOSED_NOT_FOUND)
        .bindingRecoveryRetryCondition(CHANNEL_CLOSED_NOT_FOUND)
        .consumerRecoveryRetryCondition(CHANNEL_CLOSED_NOT_FOUND)
        .queueRecoveryRetryOperation(RECOVER_CHANNEL
            .andThen(RECOVER_QUEUE)
            .andThen(RECOVER_PREVIOUS_AUTO_DELETE_QUEUES))
        .bindingRecoveryRetryOperation(RECOVER_CHANNEL
            .andThen(RECOVER_BINDING_QUEUE)
            .andThen(RECOVER_BINDING)
            .andThen(RECOVER_PREVIOUS_QUEUE_BINDINGS)
            .andThen(RECOVER_PREVIOUS_AUTO_DELETE_QUEUES))
        .consumerRecoveryRetryOperation(RECOVER_CHANNEL
            .andThen(RECOVER_CONSUMER_QUEUE)
            .andThen(RECOVER_CONSUMER)
            .andThen(RECOVER_CONSUMER_QUEUE_BINDINGS)
            .andThen(RECOVER_PREVIOUS_CONSUMERS));
}
