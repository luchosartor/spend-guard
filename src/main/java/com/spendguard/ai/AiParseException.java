package com.spendguard.ai;

/** The AI reviewer's raw response couldn't be parsed into the expected JSON contract. */
public class AiParseException extends Exception {

    public AiParseException(String message) {
        super(message);
    }

    public AiParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
