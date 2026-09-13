/*
 * Copyright 2026 Morphe. https://github.com/MorpheApp/PotHelper
 * Licensed under the Apache License, Version 2.0
 */
package com.github.catvod.spider.pot;

import java.io.IOException;

/** KeyData proto: value=2. */
public final class KeyData implements ProtoMessage {
    private final CipherKey value;

    public KeyData(CipherKey value) { this.value = value; }

    public CipherKey value() { return value; }

    public static KeyData parseFrom(ProtoReader reader) throws IOException {
        CipherKey value = null;
        while (reader.hasNext()) {
            int tag = reader.readTag();
            int fieldNumber = ProtoReader.getFieldNumber(tag);
            int wireType = ProtoReader.getWireType(tag);
            if (fieldNumber == 2) {
                value = reader.readOptionalMessage(wireType, new ProtoReader.Parser<CipherKey>() {
                    @Override public CipherKey parse(ProtoReader r) throws IOException { return CipherKey.parseFrom(r); }
                });
            } else {
                reader.skipField(wireType);
            }
        }
        return new KeyData(value);
    }

    @Override
    public void writeTo(ProtoWriter writer) throws IOException {
        if (value != null) writer.writeMessageField(2, value);
    }
}
