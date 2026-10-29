/*
 * module-info.java - JPMS Modular Declaration
 * Architecture Decision: ADR-0021 & ADR-0023
 */
module com.pharmacy.pos {
    requires javafx.controls;
    requires javafx.fxml;
    requires javafx.graphics;

    requires org.kordamp.ikonli.javafx;
    requires org.kordamp.ikonli.feather;

    requires com.fasterxml.jackson.databind;
    requires com.fasterxml.jackson.datatype.jsr310;

    requires java.net.http;
    requires java.sql;
    requires org.slf4j;

    opens com.pharmacy.pos to javafx.fxml, javafx.graphics;
    opens com.pharmacy.pos.controllers to javafx.fxml;
    opens com.pharmacy.pos.models to com.fasterxml.jackson.databind;

    exports com.pharmacy.pos;
    exports com.pharmacy.pos.models;
    exports com.pharmacy.pos.services;
    exports com.pharmacy.pos.controllers;
}

