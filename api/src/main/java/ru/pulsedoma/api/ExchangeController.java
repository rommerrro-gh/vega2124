package ru.pulsedoma.api;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.*;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import ru.pulsedoma.issues.*;
import java.security.Principal;
import java.util.*;
import java.io.IOException;
@RestController @Validated
public class ExchangeController {
 private final CaseExchangeService service;
 public ExchangeController(CaseExchangeService service){this.service=service;}
 @GetMapping("/v1/houses/{house}/exchange-channels")
 public List<Map<String,Object>> channels(@PathVariable @NotBlank String house,Principal p){return service.channels(house,p.getName());}
 @PostMapping("/v1/houses/{house}/exchange-channels")
 public List<Map<String,Object>> channelCreate(@PathVariable @NotBlank String house,@Valid @RequestBody ExchangeRequests.Channel r,Principal p){return service.channel(house,null,r,p.getName());}
 @PutMapping("/v1/houses/{house}/exchange-channels/{id}")
 public List<Map<String,Object>> channelSave(@PathVariable @NotBlank String house,@PathVariable @NotBlank String id,@Valid @RequestBody ExchangeRequests.Channel r,Principal p){return service.channel(house,id,r,p.getName());}
 @GetMapping("/v1/cases/{id}/exchange")
 public CaseExchangeService.Workspace workspace(@PathVariable @NotBlank String id,Principal p){return service.workspace(id,p.getName());}
 @PostMapping("/v1/cases/{id}/exchange")
 public CaseExchangeService.Workspace draft(@PathVariable @NotBlank String id,@Valid @RequestBody ExchangeRequests.Packet r,Principal p){return service.draft(id,null,r,p.getName());}
 @PutMapping("/v1/cases/{id}/exchange/{packet}")
 public CaseExchangeService.Workspace draftSave(@PathVariable @NotBlank String id,@PathVariable @NotBlank String packet,@Valid @RequestBody ExchangeRequests.Packet r,Principal p){return service.draft(id,packet,r,p.getName());}
 @PostMapping("/v1/exchange-packets/{id}/seal")
 public CaseExchangeService.Workspace seal(@PathVariable @NotBlank String id,@Valid @RequestBody ExchangeRequests.Decision r,Principal p){return service.decision(id,"seal",r,p.getName());}
 @PostMapping("/v1/exchange-packets/{id}/send")
 public CaseExchangeService.Workspace send(@PathVariable @NotBlank String id,@Valid @RequestBody ExchangeRequests.Decision r,Principal p){return service.decision(id,"send",r,p.getName());}
 @PostMapping("/v1/exchange-packets/{id}/receive")
 public CaseExchangeService.Workspace receive(@PathVariable @NotBlank String id,@Valid @RequestBody ExchangeRequests.Decision r,Principal p){return service.decision(id,"receive",r,p.getName());}
 @PostMapping("/v1/exchange-packets/{id}/cancel")
 public CaseExchangeService.Workspace cancel(@PathVariable @NotBlank String id,@Valid @RequestBody ExchangeRequests.Decision r,Principal p){return service.decision(id,"cancel",r,p.getName());}
 @PostMapping("/v1/exchange-packets/{id}/schedule")
 public CaseExchangeService.Workspace schedule(@PathVariable @NotBlank String id,@Valid @RequestBody ExchangeRequests.Schedule r,Principal p){return service.schedule(id,r,p.getName());}
 @PostMapping("/v1/exchange-packets/{id}/invitations")
 public CaseExchangeService.Issued invite(@PathVariable @NotBlank String id,@Valid @RequestBody ExchangeRequests.Invite r,Principal p){return service.invite(id,r,p.getName());}
 @PostMapping("/v1/exchange-invitations/{id}/revoke")
 public CaseExchangeService.Workspace revoke(@PathVariable @NotBlank String id,@Valid @RequestBody ExchangeRequests.Decision r,Principal p){return service.revoke(id,r,p.getName());}
 @PostMapping("/v1/exchange/accept")
 public Map<String,Object> accept(@Valid @RequestBody ExchangeRequests.Accept r,Principal p){return service.accept(r.token(),p.getName());}
 @GetMapping("/v1/exchange/inbox")
 public List<Map<String,Object>> inbox(Principal p){return service.inbox(p.getName());}
 @GetMapping("/v1/exchange/packets/{id}")
 public Map<String,Object> recipient(@PathVariable @NotBlank String id,Principal p){return service.recipient(id,p.getName());}
 @PostMapping("/v1/exchange/packets/{id}/acknowledge")
 public Map<String,Object> acknowledge(@PathVariable @NotBlank String id,@Valid @RequestBody ExchangeRequests.RecipientAction r,Principal p){return service.acknowledge(id,r,p.getName());}
 @PutMapping("/v1/exchange/packets/{id}/reply")
 public Map<String,Object> reply(@PathVariable @NotBlank String id,@Valid @RequestBody ExchangeRequests.Reply r,Principal p){return service.reply(id,r,p.getName());}
 @PostMapping("/v1/exchange/packets/{id}/publish")
 public Map<String,Object> publish(@PathVariable @NotBlank String id,@Valid @RequestBody ExchangeRequests.RecipientAction r,Principal p){return service.publish(id,r,p.getName());}
 @PostMapping("/v1/exchange-packets/{id}/answer")
 public CaseExchangeService.Workspace manualAnswer(@PathVariable @NotBlank String id,@Valid @RequestBody ExchangeRequests.ManualAnswer r,Principal p){return service.manualAnswer(id,r,p.getName());}
 @PostMapping("/v1/exchange-packets/{id}/review")
 public CaseExchangeService.Workspace review(@PathVariable @NotBlank String id,@Valid @RequestBody ExchangeRequests.Review r,Principal p){return service.review(id,r,p.getName());}

 @PostMapping(value="/v1/exchange/packets/{id}/files",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
 public Map<String,Object> upload(@PathVariable @NotBlank String id,@RequestParam @Min(0) int revision,@RequestParam @NotBlank @Size(max=160) String title,@RequestPart("file") MultipartFile file,Principal p)throws IOException{return service.upload(id,revision,title,file.getBytes(),file.getContentType(),p.getName());}
 @GetMapping("/v1/exchange-packets/{id}/print")
 public ResponseEntity<byte[]> print(@PathVariable @NotBlank String id,Principal p){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.parseMediaType("text/html;charset=UTF-8")).header("Content-Disposition","attachment; filename=exchange-manifest.html").header("Content-Security-Policy","default-src 'none'; sandbox").header("X-Content-Type-Options","nosniff").body(service.print(id,p.getName()).getBytes(java.nio.charset.StandardCharsets.UTF_8));}
 @GetMapping("/v1/exchange-packets/{id}/files/{file}")
 public ResponseEntity<byte[]> file(@PathVariable @NotBlank String id,@PathVariable @NotBlank String file,@RequestParam(defaultValue="false") boolean reply,Principal p){var f=service.file(id,file,reply,p.getName());return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.parseMediaType(f.mime())).header("Content-Disposition","attachment; filename=exchange-document").header("X-Content-Type-Options","nosniff").body(f.bytes());}
}
