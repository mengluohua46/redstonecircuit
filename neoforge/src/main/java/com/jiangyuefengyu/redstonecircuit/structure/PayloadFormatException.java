package com.jiangyuefengyu.redstonecircuit.structure;

import java.io.IOException;

/**
 * A payload that is present but cannot be trusted: wrong format version, a size beyond the limits, an index that points
 * outside the palette, or bytes that did not survive on disk.
 *
 * <p>An {@link IOException} on purpose, to tell the two caller questions apart: "there is no file for this structure"
 * arrives as {@code NoSuchFileException} (the cross-save case: show the missing placeholder, refuse to release), and
 * "there is a file and it is unusable" arrives as this.
 */
public class PayloadFormatException extends IOException {

    public PayloadFormatException(String message) {
        super(message);
    }

    public PayloadFormatException(String message, Throwable cause) {
        super(message, cause);
    }
}
