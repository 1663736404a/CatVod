/*
 * Copyright 2026 Morphe. https://github.com/MorpheApp/PotHelper
 * Licensed under the Apache License, Version 2.0
 */
package com.github.catvod.spider.pot;

import java.io.IOException;

/** Key proto: data=1, keyId=3. */
public final class Key implements ProtoMessage {
    private final KeyData data;
    private final Integer keyId;

    public Key(KeyData data, Integer keyId) {
        this.data = data;
        this.keyId = keyId;
    }

    public KeyData data() { return data; }
    public Integer keyId() { return keyId; }

    public static Key parseFrom(ProtoReader reader) throws IOException {
        KeyData data = null;
        Integer keyId = null;
        while (reader.hasNext()) {
            int tag = reader.readTag();
            int fieldNumber = ProtoReader.getFieldNumber(tag);
            int wireType = ProtoReader.getWireType(tag);
            if (fieldNumber == 1) {
                data = reader.readOptionalMessage(wireType, new ProtoReader.Parser<KeyData>() {
                    @Override public KeyData parse(ProtoReader r) throws IOException { return KeyData.parseFrom(r); }
                });
            } else if (fieldNumber == 3) {
                keyId = reader.readOptionalInt32(wireType);
            } else {
                reader.skipField(wireType);
            }
        }
        return new Key(data, keyId);
    }

    @Override
    public void writeTo(ProtoWriter writer) throws IOException {
        if (data != null) writer.writeMessageField(1, data);
        if (keyId != null) writer.writeInt32Field(3, keyId);
    }
}
