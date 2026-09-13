/*
 * Copyright 2026 Morphe. https://github.com/MorpheApp/PotHelper
 * Licensed under the Apache License, Version 2.0
 */
package com.github.catvod.spider.pot;

import java.io.IOException;

/** CipherKey proto: key=1, value=3. */
public final class CipherKey implements ProtoMessage {
    private final Integer key;
    private final byte[] value;

    public CipherKey(Integer key, byte[] value) {
        this.key = key;
        this.value = value;
    }

    public Integer key() { return key; }
    public byte[] value() { return value; }

    public static CipherKey parseFrom(ProtoReader reader) throws IOException {
        Integer key = null;
        byte[] value = null;
        while (reader.hasNext()) {
            int tag = reader.readTag();
            int fieldNumber = ProtoReader.getFieldNumber(tag);
            int wireType = ProtoReader.getWireType(tag);
            if (fieldNumber == 1) {
                key = reader.readOptionalInt32(wireType);
            } else if (fieldNumber == 3) {
                value = reader.readOptionalBytes(wireType);
            } else {
                reader.skipField(wireType);
            }
        }
        return new CipherKey(key, value);
    }

    @Override
    public void writeTo(ProtoWriter writer) throws IOException {
        if (key != null) writer.writeInt32Field(1, key);
        if (value != null) writer.writeBytesField(3, value);
    }
}
