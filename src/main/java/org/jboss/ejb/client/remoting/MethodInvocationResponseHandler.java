/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2011, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.jboss.ejb.client.remoting;

import org.jboss.ejb.client.*;
import org.jboss.marshalling.MarshallerFactory;
import org.jboss.marshalling.Unmarshaller;

import java.io.DataInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

/**
 * Responsible for processing a message and parsing the method invocation response from it, as per the
 * EJB remoting client protocol specification
 * <p/>
 * User: Jaikiran Pai
 *
 * IDBS Patch: This file has been modified to log the size of data received for an EJB call and how long
 *              it took to receive the data from the server.
 */
class MethodInvocationResponseHandler extends ProtocolMessageHandler {

    private static final Logs log = Logs.REMOTING;

    private final ChannelAssociation channelAssociation;
    private final MarshallerFactory marshallerFactory;

    MethodInvocationResponseHandler(final ChannelAssociation channelAssociation, final MarshallerFactory marshallerFactory) {
        this.marshallerFactory = marshallerFactory;
        this.channelAssociation = channelAssociation;
    }


    /**
     * Reads the passed <code>messageInputStream</code> and parses it for a method invocation response.
     * This method doesn't fully parse the stream and instead just parses enough so that it is able to
     * create a {@link EJBReceiverInvocationContext.ResultProducer} which can further parse the rest of the
     * stream in its {@link org.jboss.ejb.client.EJBReceiverInvocationContext.ResultProducer#getResult()} (whenever that
     * gets invoked)
     *
     *
     * @param inputStream@throws IOException If there is a problem reading from the stream
     */
    @Override
    protected void processMessage(InputStream inputStream) throws IOException {
        if (inputStream == null) {
            throw new IllegalArgumentException("Cannot read from null stream");
        }
        final DataInputStream input = new DataInputStream(inputStream);
        // read the invocation id
        final short invocationId = input.readShort();

        final EJBReceiverInvocationContext receiverInvocationContext = this.channelAssociation.getEJBReceiverInvocationContext(invocationId);
        EJBClientInvocationContext clientInvocationContext = null;
        if (receiverInvocationContext != null) {
            clientInvocationContext = receiverInvocationContext.getClientInvocationContext();
        }
        // create a ResultProducer which can unmarshall and return the result, later
        final EJBReceiverInvocationContext.ResultProducer resultProducer = new MethodInvocationResultProducer(clientInvocationContext, input);
        // make it known that the result is available
        this.channelAssociation.resultReady(invocationId, resultProducer);
    }

    /**
     * A result producer which parses a input stream and returns a method invocation response as a result
     */
    private class MethodInvocationResultProducer implements EJBReceiverInvocationContext.ResultProducer {

        private final DataInputStream input;
        private final EJBClientInvocationContext clientInvocationContext;

        MethodInvocationResultProducer(final EJBClientInvocationContext clientInvocationContext, final DataInputStream input) {
            this.input = input;
            this.clientInvocationContext = clientInvocationContext;
        }

        @Override
        public Object getResult() throws Exception {
            try {
                long startTimeMillis = System.currentTimeMillis();
                DataInputStream dataInputStream = this.input;

                CountingInputStream byteCountingStream = null;
                if (log.isDebugEnabled()) {
                    byteCountingStream = new CountingInputStream(this.input);
                    dataInputStream = new DataInputStream(byteCountingStream);
                }

                // prepare the unmarshaller
                final Unmarshaller unmarshaller = MethodInvocationResponseHandler.this.prepareForUnMarshalling(MethodInvocationResponseHandler.this.marshallerFactory,
                        new DataInputStream(dataInputStream));

                // read the result
                final Object result = unmarshaller.readObject();
                // read the attachments
                final Map<String, Object> attachments = MethodInvocationResponseHandler.this.readAttachments(unmarshaller);

                // finish unmarshalling
                unmarshaller.finish();

                // IDBS Patch - debug read size and timings for performance purposes.
                if (log.isDebugEnabled()) {
                    log.debug("Read " + byteCountingStream.getBytesRead() + " bytes in " + (System.currentTimeMillis() - startTimeMillis) + "ms");
                }

                // see if there's a weak affinity passed as an attachment. If yes, then attach it to the client invocation
                // context
                if (this.clientInvocationContext != null && attachments != null && attachments.containsKey(Affinity.WEAK_AFFINITY_CONTEXT_KEY)) {
                    final Affinity weakAffinity = (Affinity) attachments.get(Affinity.WEAK_AFFINITY_CONTEXT_KEY);
                    this.clientInvocationContext.putAttachment(AttachmentKeys.WEAK_AFFINITY, weakAffinity);
                }
                // return the result
                return result;
            } finally {
                this.input.close();
            }
        }

        @Override
        public void discardResult() {
        }
    }

    private static class CountingInputStream extends FilterInputStream {
        private int bytesRead;

        public CountingInputStream(InputStream in) {
            super(in);
        }

        public int getBytesRead() {
            return bytesRead;
        }

        @Override
        public int read() throws IOException {
            bytesRead++;
            return super.read();
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int result = super.read(b, off, len);
            bytesRead += result;
            return result;
        }
    }
}
