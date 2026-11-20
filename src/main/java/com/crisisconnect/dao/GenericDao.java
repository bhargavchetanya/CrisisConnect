package com.crisisconnect.dao;

import java.util.List;
import java.util.Optional;

/**
 * Generic DAO contract (Unit 4 - DAO design pattern + Unit 2 - generics).
 * The service layer only talks to these interfaces, never to SQL directly,
 * so the database (SQLite -> MySQL -> cloud) can be swapped without touching business logic.
 *
 * @param <T>  entity type
 * @param <ID> primary-key type
 */
public interface GenericDao<T, ID> {

    T save(T entity);                 // INSERT

    boolean update(T entity);         // UPDATE

    boolean delete(ID id);            // DELETE

    Optional<T> findById(ID id);      // SELECT ... WHERE id = ?

    List<T> findAll();                // SELECT *

    int count();
}
