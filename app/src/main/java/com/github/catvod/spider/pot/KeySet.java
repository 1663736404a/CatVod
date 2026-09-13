/*
 * Copyright 2026 Morphe. https://github.com/MorpheApp/PotHelper
 * Licensed under the Apache License, Version 2.0
 */
package com.github.catvod.spider.pot;

import android.util.Pair;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.crypto.spec.SecretKeySpec;

/** KeySet proto: keyList=2. Holds the Tink AesGcmKey material. */
public final class KeySet implements ProtoMessage {
    private final List<Key> keyList;

    public KeySet(List<Key> keyList) { this.keyList = keyList; }

    public List<Key> keyList() { return keyList; }

    public static KeySet parseFrom(byte[] data) throws IOException {
        return parseFrom(new ProtoReader(data));
    }

    public static KeySet parseFrom(ProtoReader reader) throws IOException {
        List<Key> keyList = new ArrayList<>();
        while (reader.hasNext()) {
            int tag = reader.readTag();
            int fieldNumber = ProtoReader.getFieldNumber(tag);
            int wireType = ProtoReader.getWireType(tag);
            if (fieldNumber == 2) {
                Key key = reader.readOptionalMessage(wireType, new ProtoReader.Parser<Key>() {
                    @Override public Key parse(ProtoReader r) throws IOException { return Key.parseFrom(r); }
                });
                if (key != null) keyList.add(key);
            } else {
                reader.skipField(wireType);
            }
        }
        return new KeySet(Collections.unmodifiableList(keyList));
    }

    public Pair<Integer, SecretKeySpec> getKeyPair() {
        Key key = keyList.get(0);
        Integer keyId = key.keyId();
        KeyData keyData = key.data();
        CipherKey cipherKey = keyData.value();
        byte[] keyBytes = cipherKey.value();
        return new Pair<>(keyId, new SecretKeySpec(keyBytes, "AES"));
    }

    @Override
    public void writeTo(ProtoWriter writer) throws IOException {
        if (keyList != null) for (Key key : keyList) writer.writeMessageField(2, key);
    }
}
