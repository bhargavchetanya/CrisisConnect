package com.crisisconnect.db;

import com.crisisconnect.exception.AllocationFailedException;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Programmatic transaction management (Unit 4 - Transactions).
 *
 * The connection is bound to the current thread (ThreadLocal) so that every DAO
 * call made inside {@link #inTransaction(TransactionalWork)} uses the SAME
 * connection -> all statements commit together or roll back together (ACID).
 */
public final class TransactionManager {

    private static final Logger LOG = Logger.getLogger(TransactionManager.class.getName());
    private static final ThreadLocal<Connection> CURRENT = new ThreadLocal<>();

    private TransactionManager() { }

    /** A unit of work that returns a value and may throw any exception. */
    @FunctionalInterface
    public interface TransactionalWork<T> {
        T run() throws Exception;
    }

    /** Connection of the running transaction, or null if none. */
    static Connection current() { return CURRENT.get(); }

    public static <T> T inTransaction(TransactionalWork<T> work) throws AllocationFailedException {
        if (CURRENT.get() != null) {           // nested call -> join existing transaction
            try { return work.run(); }
            catch (Exception e) { throw new AllocationFailedException(e.getMessage(), e); }
        }
        Connection conn = null;
        try {
            conn = DatabaseManager.getInstance().getConnection();
            conn.setAutoCommit(false);
            CURRENT.set(conn);
            T result = work.run();
            conn.commit();
            LOG.fine("Transaction committed");
            return result;
        } catch (Exception e) {
            if (conn != null) {
                try {
                    conn.rollback();
                    LOG.warning("Transaction ROLLED BACK: " + e.getMessage());
                } catch (SQLException re) {
                    LOG.log(Level.SEVERE, "Rollback failed", re);
                }
            }
            throw new AllocationFailedException("Transaction failed: " + e.getMessage(), e);
        } finally {
            CURRENT.remove();
            if (conn != null) {
                try { conn.setAutoCommit(true); conn.close(); } catch (SQLException ignored) { }
            }
        }
    }
}
