package com.crisisconnect.exception;

public class EntityNotFoundException extends CrisisConnectException {
    private static final long serialVersionUID = 1L;

    public EntityNotFoundException(String entity, Object id) {
        super(entity + " with id " + id + " was not found");
    }
}
