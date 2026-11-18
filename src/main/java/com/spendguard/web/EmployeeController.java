package com.spendguard.web;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.spendguard.domain.Card;
import com.spendguard.domain.Merchant;
import com.spendguard.domain.Transaction;
import com.spendguard.exception.NotFoundException;
import com.spendguard.repository.CardRepository;
import com.spendguard.repository.EmployeeRepository;
import com.spendguard.repository.MerchantRepository;
import com.spendguard.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/employees")
@RequiredArgsConstructor
public class EmployeeController {

    private final EmployeeRepository employeeRepository;
    private final CardRepository cardRepository;
    private final TransactionRepository transactionRepository;
    private final MerchantRepository merchantRepository;

    /** Analyst context when reviewing a flag - no pagination needed for this demo. */
    @GetMapping("/{id}/transactions")
    public List<EmployeeTransactionResponse> transactions(@PathVariable Long id) {
        employeeRepository.findById(id).orElseThrow(() -> new NotFoundException("Employee not found: " + id));

        List<Long> cardIds = cardRepository.findByEmployeeId(id).stream().map(Card::getId).toList();
        List<Transaction> transactions = transactionRepository.findByCardIdInOrderByOccurredAtDesc(cardIds);
        if (transactions.isEmpty()) {
            return List.of();
        }

        Map<Long, Merchant> merchantsById = merchantRepository.findAllById(
                transactions.stream().map(Transaction::getMerchantId).collect(Collectors.toSet())
        ).stream().collect(Collectors.toMap(Merchant::getId, m -> m));

        return transactions.stream().map(t -> {
            Merchant merchant = merchantsById.get(t.getMerchantId());
            return new EmployeeTransactionResponse(t.getId(), merchant.getId(), merchant.getName(), merchant.getCategory(),
                    t.getAmount(), t.getCurrency(), t.getOccurredAt(), t.getCountry());
        }).toList();
    }
}
