/*
 * The MIT License
 *
 * Copyright (c) 2013-2014, CloudBees, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package org.jenkinsci.plugins.workflow.support.pickles.serialization;

import org.jenkinsci.plugins.workflow.pickles.Pickle;
import org.jenkinsci.plugins.workflow.support.concurrent.Futures;
import com.google.common.base.Function;
import com.google.common.util.concurrent.ListenableFuture;
import org.apache.commons.io.IOUtils;
import org.jboss.marshalling.Marshalling;
import org.jboss.marshalling.MarshallingConfiguration;
import org.jboss.marshalling.SimpleClassResolver;
import org.jboss.marshalling.Unmarshaller;
import org.jboss.marshalling.river.RiverMarshallerFactory;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import static org.apache.commons.io.IOUtils.*;

/**
 * @author Kohsuke Kawaguchi
 */
public class RiverReader {
    private final File file;
    private final ClassLoader classLoader;

    public RiverReader(File f, ClassLoader classLoader) throws IOException {
        this.file = f;
        this.classLoader = classLoader;
    }

    private int parseHeader(DataInputStream din) throws IOException {
        if (din.readLong()!= RiverWriter.HEADER)
            throw new IOException("Invalid stream header");

        short v = din.readShort();
        if (v!=1)
            throw new IOException("Unexpected stream version: "+v);

        return din.readInt();
    }

    /**
     * Step 1. Start unmarshalling pickles in the persisted stream,
     * and return the future that will signal when that is all complete.
     *
     * Once the pickles are restored, the future yields {@link Unmarshaller}
     * that can be then used to load the objects persisted by {@link RiverWriter}.
     */
    public ListenableFuture<Unmarshaller> restorePickles() throws IOException {
        DataInputStream din = new DataInputStream(openStreamAt(0));
        int offset = parseHeader(din);

        // load the pickle stream
        List<Pickle> pickles = readPickles(offset);
        final PickleResolver evr = new PickleResolver(pickles);

        // prepare the unmarshaller to load the main stream, by using yet-fulfilled PickleResolver
        MarshallingConfiguration config = new MarshallingConfiguration();
        config.setClassResolver(new SimpleClassResolver(classLoader));
        //config.setSerializabilityChecker(new SerializabilityCheckerImpl());
        config.setObjectResolver(evr);
        final Unmarshaller eu = new RiverMarshallerFactory().createUnmarshaller(config);
        eu.start(Marshalling.createByteInput(din));

        // start rehydrating, and when done make the unmarshaller available
        return Futures.transform(evr.rehydrate(), new Function<PickleResolver, Unmarshaller>() {
            public Unmarshaller apply(PickleResolver input) {
                return eu;
            }
        });
    }

    private List<Pickle> readPickles(int offset) throws IOException {
        BufferedInputStream es = openStreamAt(offset);
        try {
            MarshallingConfiguration config = new MarshallingConfiguration();
            config.setClassResolver(new SimpleClassResolver(classLoader));
            Unmarshaller eu = new RiverMarshallerFactory().createUnmarshaller(config);
            try {
                eu.start(Marshalling.createByteInput(es));
                return (List<Pickle>)eu.readObject();
            } catch (ClassNotFoundException e) {
                throw new IOException("Failed to read the stream",e);
            } finally {
                eu.finish();
            }
        } finally {
            closeQuietly(es);
        }
    }

    private BufferedInputStream openStreamAt(int offset) throws IOException {
        InputStream in = new FileInputStream(file);
        IOUtils.skipFully(in, offset);
        return new BufferedInputStream(in);
    }
}
