package lk.wms.outbound.api;

import jakarta.validation.constraints.Size;
import java.util.List;
import lk.wms.common.security.CurrentUser;
import lk.wms.outbound.service.PickService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The supervisor's task board (v1.0 task 6): tasks:read. Assigning and priorities are on the pick lists. */
@RestController
@RequestMapping("/api/v1/task-board")
public class TaskBoardController {

    private final PickService picks;

    public TaskBoardController(PickService picks) {
        this.picks = picks;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('tasks:read')")
    public PickService.Board board(@RequestParam("warehouse") @Size(max = 26) String warehouseId) {
        return picks.board(CurrentUser.require(), warehouseId);
    }

    /** People a pick list can be given to in this warehouse. */
    @GetMapping("/pickers")
    @PreAuthorize("hasAuthority('tasks:assign')")
    public List<PickService.Person> pickers(@RequestParam("warehouse") @Size(max = 26) String warehouseId) {
        return picks.pickers(CurrentUser.require(), warehouseId);
    }
}
