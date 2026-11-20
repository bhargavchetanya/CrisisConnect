package com.crisisconnect.model;

import com.crisisconnect.exception.InsufficientResourceException;

/** Behaviour of anything whose quantity can be reserved for an emergency. */
public interface Allocatable {

    int getAvailableQuantity();

    default boolean canAllocate(int quantity) {
        return quantity > 0 && quantity <= getAvailableQuantity();
    }

    void allocate(int quantity) throws InsufficientResourceException;

    void release(int quantity);
}
