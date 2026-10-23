package ru.pulsedoma.issues;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Run only with writers stopped and a storage backup. Live references always win. */
@Service
public class AttachmentMaintenance {
 private final JdbcTemplate db;private final AttachmentStore store;
 public AttachmentMaintenance(JdbcTemplate db,AttachmentStore store){this.db=db;this.store=store;}
 public Set<String> referenced(){Set<String> result=new HashSet<>();for(var row:db.queryForList("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name NOT LIKE '%fts%'")){String table=(String)row.get("name");if(db.queryForList("PRAGMA table_info('"+table+"')").stream().anyMatch(c->"storage_key".equals(c.get("name"))))result.addAll(db.query("SELECT storage_key FROM "+table+" WHERE storage_key IS NOT NULL",(r,n)->r.getString(1)));}return result;}
 public record Result(int stored,int referenced,int removed){}
 public Result prune(){var keep=referenced();var keys=store.keys();int removed=0;for(String key:keys)if(!keep.contains(key)){// Recheck each key; a current reference must never be removed.
   if(referenced().contains(key))continue;store.delete(key);removed++;
  }return new Result(keys.size(),keep.size(),removed);}
}
