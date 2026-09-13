/*
 * Copyright 2026 Morphe. https://github.com/MorpheApp/PotHelper
 * Licensed under the Apache License, Version 2.0
 */
package com.github.catvod.spider.pot;

import java.io.IOException;

/** PoTokenResult proto: integrityToken=1. */
public final class PoTokenResult implements ProtoMessage {
    private final IntegrityToken integrityToken;

    public PoTokenResult(IntegrityToken integrityToken) {
        this.integrityToken = integrityToken;
    }

    public IntegrityToken integrityToken() { return integrityToken; }

    @Override
    public void writeTo(ProtoWriter writer) throws IOException {
        if (integrityToken != null) writer.writeMessageField(1, integrityToken);
    }
}
