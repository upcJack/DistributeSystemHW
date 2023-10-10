// Copyright (c) 2023 VMware, Inc. or its affiliates.  All rights reserved.
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
package com.rabbitmq.client.observation;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Consumer;
import com.rabbitmq.client.GetResponse;
import java.io.IOException;

final class NoOpObservationCollector implements ObservationCollector {

  @Override
  public void publish(
      PublishCall call,
      AMQP.Basic.Publish publish,
      AMQP.BasicProperties properties,
      byte[] body,
      ConnectionInfo connectionInfo)
      throws IOException {
    call.publish(properties);
  }

  @Override
  public Consumer basicConsume(String queue, String consumerTag, Consumer consumer) {
    return consumer;
  }

  @Override
  public GetResponse basicGet(BasicGetCall call, String queue) {
    return call.get();
  }
}
