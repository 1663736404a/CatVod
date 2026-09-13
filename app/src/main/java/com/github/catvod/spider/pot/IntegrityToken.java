/*
 * Copyright 2026 Morphe. https://github.com/MorpheApp/PotHelper
 * Licensed under the Apache License, Version 2.0
 */
package com.github.catvod.spider.pot;

import java.io.IOException;

/** IntegrityToken proto: encryptData=1, tokenData=2. */
public final class IntegrityToken implements ProtoMessage {
    private final byte[] encryptData;
    private final byte[] tokenData;

    public IntegrityToken(byte[] encryptData, byte[] tokenData) {
        this.encryptData = encryptData;
        this.tokenData = tokenData;
    }

    public byte[] encryptData() { return encryptData; }
    public byte[] tokenData() { return tokenData; }

    @Override
    public void writeTo(ProtoWriter writer) throws IOException {
        if (encryptData != null) writer.writeBytesField(1, encryptData);
        if (tokenData != null) writer.writeBytesField(2, tokenData);
    }
}
