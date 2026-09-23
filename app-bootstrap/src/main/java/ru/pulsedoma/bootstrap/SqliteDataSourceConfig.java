package ru.pulsedoma.bootstrap;

import com.zaxxer.hikari.HikariDataSource;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import javax.sql.DataSource;

@Configuration
public class SqliteDataSourceConfig {
    // NFR-011: one writer connection, WAL and per-connection SQLite pragmas.
    @Bean
    public DataSource dataSource(@Value("${spring.datasource.url}") String url) {
        SQLiteConfig sqlite = new SQLiteConfig();
        sqlite.setJournalMode(SQLiteConfig.JournalMode.WAL);
        sqlite.setBusyTimeout(5000);
        sqlite.enforceForeignKeys(true);
        SQLiteDataSource source = new SQLiteDataSource(sqlite);
        source.setUrl(url);
        HikariDataSource pool = new HikariDataSource();
        pool.setDataSource(source);
        pool.setMaximumPoolSize(1);
        pool.setMinimumIdle(1);
        pool.setPoolName("sqlite-single-writer");
        return pool;
    }
}
