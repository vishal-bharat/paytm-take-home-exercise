package in.me.vishal.seats.controller;

import in.me.vishal.seats.dto.CreateShowRequest;
import in.me.vishal.seats.dto.ShowCreatedResponse;
import in.me.vishal.seats.dto.ShowResponse;
import in.me.vishal.seats.service.ShowService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
 
import java.net.URI;
 
@RestController
@RequestMapping("/shows")
public class ShowController {
 
    private final ShowService showService;
 
    public ShowController(ShowService showService) {
        this.showService = showService;
    }
 
    @PostMapping
    public ResponseEntity<ShowCreatedResponse> create(@RequestBody CreateShowRequest req) {
        ShowCreatedResponse created = showService.create(req);
        return ResponseEntity.created(URI.create("/shows/" + created.id())).body(created);
    }
    
    @GetMapping("/{id}")
    public ShowResponse get(@PathVariable long id) {
        return showService.get(id);
    }
}
