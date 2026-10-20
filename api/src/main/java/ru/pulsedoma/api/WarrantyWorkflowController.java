package ru.pulsedoma.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.*;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import ru.pulsedoma.issues.*;
import java.io.IOException;
import java.security.Principal;
import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@Validated
public class WarrantyWorkflowController {
    private final WarrantyWorkflowService service;
    public WarrantyWorkflowController(WarrantyWorkflowService service){this.service=service;}
    @GetMapping("/v1/workflow/houses")
    public List<WarrantyWorkflowService.House> houses(Principal p){return service.houses(p.getName());}
    @GetMapping("/v1/houses/{house}/workflow/staff")
    public List<WarrantyWorkflowService.Staff> staff(@PathVariable @NotBlank String house,Principal p){return service.staff(house,p.getName());}
    @PostMapping("/v1/houses/{house}/workflow/staff/self")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void self(@PathVariable @NotBlank String house,Principal p){service.self(house,p.getName());}
    @PostMapping("/v1/houses/{house}/workflow/staff/{user}/revoke")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable @NotBlank String house,@PathVariable @NotBlank String user,@RequestParam CaseFunction function,Principal p){service.revokeStaff(house,user,function,p.getName());}
    @GetMapping("/v1/houses/{house}/workflow/invitations")
    public List<WarrantyWorkflowService.Invitation> invitations(@PathVariable @NotBlank String house,Principal p){return service.invitations(house,p.getName());}
    @PostMapping("/v1/houses/{house}/workflow/invitations")
    @ResponseStatus(HttpStatus.CREATED)
    public WarrantyWorkflowService.CreatedInvitation invite(@PathVariable @NotBlank String house,@Valid @RequestBody WorkflowRequests.Invite r,Principal p){return service.invite(house,r,p.getName());}
    @PostMapping("/v1/workflow/invitations/{id}/revoke")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeInvitation(@PathVariable @NotBlank String id,Principal p){service.revokeInvitation(id,p.getName());}
    @PostMapping("/v1/workflow/invitations/accept")
    public WarrantyWorkflowService.House accept(@Valid @RequestBody WorkflowRequests.Accept r,Principal p){return service.accept(r.token(),p.getName());}
    @GetMapping("/v1/houses/{house}/warranties")
    public List<WarrantyWorkflowService.Warranty> warranties(@PathVariable @NotBlank String house,Principal p){return service.warranties(house,p.getName());}
    @PostMapping("/v1/houses/{house}/warranties")
    @ResponseStatus(HttpStatus.CREATED)
    public WarrantyWorkflowService.Warranty createWarranty(@PathVariable @NotBlank String house,@Valid @RequestBody WorkflowRequests.Warranty r,Principal p){return service.saveWarranty(house,null,r,p.getName());}
    @PutMapping("/v1/houses/{house}/warranties/{id}")
    public WarrantyWorkflowService.Warranty warranty(@PathVariable @NotBlank String house,@PathVariable @NotBlank String id,@Valid @RequestBody WorkflowRequests.Warranty r,Principal p){return service.saveWarranty(house,id,r,p.getName());}
    @PostMapping(value="/v1/warranties/{id}/documents",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public WarrantyWorkflowService.Warranty warrantyUpload(@PathVariable @NotBlank String id,@RequestPart("file") MultipartFile file,@RequestParam @NotBlank @Size(max=160) String title,@RequestParam @Min(0) int revision,Principal p)throws IOException{return service.uploadWarranty(id,title,file.getBytes(),file.getContentType(),revision,p.getName());}
    @GetMapping("/v1/warranty-documents/{id}")
    public ResponseEntity<byte[]> warrantyFile(@PathVariable @NotBlank String id,Principal p){var f=service.warrantyFile(id,p.getName());String ext=f.mime().equals("application/pdf")?"pdf":f.mime().equals("image/png")?"png":"jpg";return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.parseMediaType(f.mime())).header("X-Content-Type-Options","nosniff").header("Content-Disposition","attachment; filename=\"warranty-document."+ext+"\"").body(f.bytes());}
    @GetMapping("/v1/cases/{id}/workflow")
    public WarrantyWorkflowService.Workspace workspace(@PathVariable @NotBlank String id,Principal p){return service.workspace(id,p.getName());}
    @PostMapping("/v1/cases/{id}/warranty-reviews")
    public WarrantyWorkflowService.Workspace review(@PathVariable @NotBlank String id,@Valid @RequestBody WorkflowRequests.Review r,Principal p){return service.review(id,r,p.getName());}
    @PostMapping("/v1/cases/{id}/handoffs")
    public WarrantyWorkflowService.Workspace handoff(@PathVariable @NotBlank String id,@Valid @RequestBody WorkflowRequests.Handoff r,Principal p){return service.createHandoff(id,r,p.getName());}
    @PatchMapping("/v1/handoffs/{id}")
    public WarrantyWorkflowService.Workspace handoffUpdate(@PathVariable @NotBlank String id,@Valid @RequestBody WorkflowRequests.HandoffUpdate r,Principal p){return service.handoff(id,r,p.getName());}
    @GetMapping("/v1/handoffs/{id}/print")
    public ResponseEntity<byte[]> printHandoff(@PathVariable @NotBlank String id,Principal p){return printable(service.printHandoff(id,p.getName()),"handoff.html");}
    @PostMapping("/v1/cases/{id}/inspections")
    public WarrantyWorkflowService.Workspace inspection(@PathVariable @NotBlank String id,@Valid @RequestBody WorkflowRequests.Inspection r,Principal p){return service.planInspection(id,null,r,p.getName());}
    @PutMapping("/v1/cases/{caseId}/inspections/{id}")
    public WarrantyWorkflowService.Workspace reschedule(@PathVariable @NotBlank String caseId,@PathVariable @NotBlank String id,@Valid @RequestBody WorkflowRequests.Inspection r,Principal p){return service.planInspection(caseId,id,r,p.getName());}
    @PatchMapping("/v1/inspections/{id}")
    public WarrantyWorkflowService.Workspace inspectionState(@PathVariable @NotBlank String id,@Valid @RequestBody WorkflowRequests.InspectionUpdate r,Principal p){return service.inspectionState(id,r,p.getName());}
    @PostMapping("/v1/inspections/{id}/response")
    public WarrantyWorkflowService.Workspace response(@PathVariable @NotBlank String id,@Valid @RequestBody WorkflowRequests.InspectionResponse r,Principal p){return service.inspectionResponse(id,r,p.getName());}
    @PostMapping("/v1/inspections/{id}/acts")
    public WarrantyWorkflowService.Workspace act(@PathVariable @NotBlank String id,@Valid @RequestBody WorkflowRequests.Act r,Principal p){return service.draftAct(id,r,p.getName());}
    @PutMapping("/v1/inspection-acts/{id}")
    public WarrantyWorkflowService.Workspace editAct(@PathVariable @NotBlank String id,@Valid @RequestBody WorkflowRequests.Act r,Principal p){return service.editAct(id,r,p.getName());}
    @PostMapping("/v1/inspection-acts/{id}/publish")
    public WarrantyWorkflowService.Workspace publish(@PathVariable @NotBlank String id,@Valid @RequestBody WorkflowRequests.Revision r,Principal p){return service.publishAct(id,r,p.getName());}
    @GetMapping("/v1/inspection-acts/{id}/print")
    public ResponseEntity<byte[]> print(@PathVariable @NotBlank String id,Principal p){return printable(service.printAct(id,p.getName()),"inspection-act.html");}
    @PostMapping("/v1/cases/{id}/work-orders")
    public WarrantyWorkflowService.Workspace work(@PathVariable @NotBlank String id,@Valid @RequestBody WorkflowRequests.Work r,Principal p){return service.createWork(id,r,p.getName());}
    @PatchMapping("/v1/work-orders/{id}")
    public WarrantyWorkflowService.Workspace workUpdate(@PathVariable @NotBlank String id,@Valid @RequestBody WorkflowRequests.WorkUpdate r,Principal p){return service.updateWork(id,r,p.getName());}
    private ResponseEntity<byte[]> printable(String html,String filename){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.parseMediaType("text/html;charset=UTF-8")).header("Content-Disposition","attachment; filename=\""+filename+"\"").header("Content-Security-Policy","default-src 'none'; style-src 'unsafe-inline'; sandbox").header("X-Content-Type-Options","nosniff").body(html.getBytes(StandardCharsets.UTF_8));}
}
