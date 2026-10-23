package ru.pulsedoma.api;

import jakarta.validation.constraints.NotBlank;
import org.springframework.stereotype.Controller;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/** Reloadable page addresses. Data still requires the authenticated /v1 API. */
@Controller
@Validated
public class ApplicationPagesController {
    public enum Page {
        home,
        cases,
        events,
        profile,
        settings,
        houses,
        roles,
        notifications,
        help,
        about,
        contacts,
        passport,
        tariffs,
        payment,
        similar
    }

    public enum Workspace {
        dispatcher,
        work,
        admin,
        uk,
        system,
        exchange
    }

    public enum WorkspacePage {
        queue,
        work,
        control,
        profile,
        settings,
        houses,
        roles,
        notifications,
        help,
        about
    }

    public enum CaseSection {
        visits,
        messages,
        documents,
        history,
        billing
    }

    public enum DetailKind {
        cases,
        shared,
        polls,
        notices
    }

    @GetMapping(
            "/miniapp/{page:home|cases|events|profile|settings|houses|roles|notifications|help|about|contacts|passport|tariffs|payment|similar}")
    public String resident(@PathVariable Page page) {
        return "forward:/miniapp/index.html";
    }

    @GetMapping("/miniapp/new")
    public String compose() {
        return "forward:/miniapp/index.html";
    }

    @GetMapping("/miniapp/{kind:cases|shared|polls|notices}/{id}")
    public String detail(@PathVariable DetailKind kind, @PathVariable @NotBlank String id) {
        return "forward:/miniapp/index.html";
    }

    @GetMapping("/miniapp/cases/{id}/{section:visits|messages|documents|history|billing}")
    public String section(@PathVariable @NotBlank String id, @PathVariable CaseSection section) {
        return "forward:/miniapp/index.html";
    }

    @GetMapping({
        "/{workspace:dispatcher|work}/{page:queue|work|control}",
        "/{workspace:admin|uk|system|exchange}/{page:queue}",
        "/{workspace:dispatcher|work|admin|uk|system|exchange}/{page:profile|settings|houses|roles|notifications|help|about}"
    })
    public String workspace(@PathVariable Workspace workspace, @PathVariable WorkspacePage page) {
        return "forward:/" + workspace.name() + "/index.html";
    }
}
