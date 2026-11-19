package com.spendguard.web;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.spendguard.domain.Card;
import com.spendguard.domain.Company;
import com.spendguard.domain.Employee;
import com.spendguard.domain.FlagStatus;
import com.spendguard.domain.Merchant;
import com.spendguard.domain.Transaction;
import com.spendguard.domain.TransactionFlag;
import com.spendguard.exception.ConflictException;
import com.spendguard.exception.NotFoundException;
import com.spendguard.repository.CardRepository;
import com.spendguard.repository.CompanyRepository;
import com.spendguard.repository.EmployeeRepository;
import com.spendguard.repository.MerchantRepository;
import com.spendguard.repository.TransactionFlagRepository;
import com.spendguard.repository.TransactionRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/flags")
@RequiredArgsConstructor
public class FlagController {

    private final TransactionFlagRepository flagRepository;
    private final TransactionRepository transactionRepository;
    private final CardRepository cardRepository;
    private final EmployeeRepository employeeRepository;
    private final MerchantRepository merchantRepository;
    private final CompanyRepository companyRepository;

    @GetMapping
    public List<FlagSummaryResponse> list(@RequestParam(defaultValue = "PENDING") FlagStatus status) {
        List<TransactionFlag> flags = flagRepository.findByStatusOrderByFinalScoreDesc(status);
        if (flags.isEmpty()) {
            return List.of();
        }

        Map<Long, Transaction> txById = loadTransactions(flags);
        Map<Long, Card> cardById = loadCards(txById.values());
        Map<Long, Employee> employeeById = loadEmployees(cardById.values());
        Map<Long, Company> companyById = loadCompanies(employeeById.values());
        Map<Long, Merchant> merchantById = loadMerchants(txById.values());

        return flags.stream().map(flag -> {
            Transaction tx = txById.get(flag.getTransactionId());
            Card card = cardById.get(tx.getCardId());
            Employee employee = employeeById.get(card.getEmployeeId());
            Company company = companyById.get(employee.getCompanyId());
            Merchant merchant = merchantById.get(tx.getMerchantId());
            return new FlagSummaryResponse(
                    flag.getId(), tx.getId(), employee.getFullName(), company.getName(), merchant.getName(), merchant.getCategory(),
                    tx.getAmount(), tx.getCurrency(), tx.getOccurredAt(), flag.getRuleScore(), flag.getAiRiskScore(),
                    flag.getSemanticMismatch(), flag.getFinalScore(), flag.getStatus());
        }).toList();
    }

    @GetMapping("/{id}")
    public FlagDetailResponse detail(@PathVariable Long id) {
        TransactionFlag flag = flagRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Flag not found: " + id));
        Transaction tx = transactionRepository.findById(flag.getTransactionId())
                .orElseThrow(() -> new NotFoundException("Transaction not found: " + flag.getTransactionId()));
        Card card = cardRepository.findById(tx.getCardId())
                .orElseThrow(() -> new NotFoundException("Card not found: " + tx.getCardId()));
        Employee employee = employeeRepository.findById(card.getEmployeeId())
                .orElseThrow(() -> new NotFoundException("Employee not found: " + card.getEmployeeId()));
        Company company = companyRepository.findById(employee.getCompanyId())
                .orElseThrow(() -> new NotFoundException("Company not found: " + employee.getCompanyId()));
        Merchant merchant = merchantRepository.findById(tx.getMerchantId())
                .orElseThrow(() -> new NotFoundException("Merchant not found: " + tx.getMerchantId()));

        return new FlagDetailResponse(
                flag.getId(), tx.getId(), employee.getFullName(), company.getName(), merchant.getName(), merchant.getCategory(),
                tx.getAmount(), tx.getCurrency(), tx.getOccurredAt(), flag.getRuleScore(), flag.getAiRiskScore(),
                flag.getSemanticMismatch(), flag.getAiExplanation(), flag.getFinalScore(), flag.getStatus(),
                // Stored at scoring time, not recomputed live - see the Javadoc on TransactionFlag.
                new SignalBreakdownResponse(flag.getAmountZScore(), flag.isVelocityTriggered(),
                        flag.isDuplicateTriggered(), flag.getGeoAnomalyType(), flag.isPolicyViolation()),
                new EmployeeInfoResponse(employee.getId(), employee.getFullName(), employee.getDepartment(), employee.getRole()),
                new MerchantInfoResponse(merchant.getId(), merchant.getName(), merchant.getCategory(), merchant.getCountry(), merchant.getPolicyLimit()),
                new TransactionInfoResponse(tx.getId(), tx.getAmount(), tx.getCurrency(), tx.getOccurredAt(), tx.getCountry()));
    }

    @PostMapping("/{id}/resolve")
    @Transactional
    public ResolveFlagResponse resolve(@PathVariable Long id, @Valid @RequestBody ResolveFlagRequest request) {
        if (request.decision() != FlagStatus.APPROVED && request.decision() != FlagStatus.REJECTED) {
            throw new IllegalArgumentException("decision must be APPROVED or REJECTED");
        }
        TransactionFlag flag = flagRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Flag not found: " + id));
        if (flag.getStatus() != FlagStatus.PENDING) {
            throw new ConflictException("Flag " + id + " is not PENDING (current status: " + flag.getStatus() + ")");
        }

        flag.setStatus(request.decision());
        flag.setResolvedAt(Instant.now());
        flag.setResolvedNote(request.note());
        flagRepository.save(flag);

        return new ResolveFlagResponse(flag.getId(), flag.getStatus(), flag.getResolvedAt(), flag.getResolvedNote());
    }

    private Map<Long, Transaction> loadTransactions(Collection<TransactionFlag> flags) {
        Set<Long> ids = flags.stream().map(TransactionFlag::getTransactionId).collect(Collectors.toSet());
        return transactionRepository.findAllById(ids).stream().collect(Collectors.toMap(Transaction::getId, t -> t));
    }

    private Map<Long, Card> loadCards(Collection<Transaction> transactions) {
        Set<Long> ids = transactions.stream().map(Transaction::getCardId).collect(Collectors.toSet());
        return cardRepository.findAllById(ids).stream().collect(Collectors.toMap(Card::getId, c -> c));
    }

    private Map<Long, Employee> loadEmployees(Collection<Card> cards) {
        Set<Long> ids = cards.stream().map(Card::getEmployeeId).collect(Collectors.toSet());
        return employeeRepository.findAllById(ids).stream().collect(Collectors.toMap(Employee::getId, e -> e));
    }

    private Map<Long, Merchant> loadMerchants(Collection<Transaction> transactions) {
        Set<Long> ids = transactions.stream().map(Transaction::getMerchantId).collect(Collectors.toSet());
        return merchantRepository.findAllById(ids).stream().collect(Collectors.toMap(Merchant::getId, m -> m));
    }

    private Map<Long, Company> loadCompanies(Collection<Employee> employees) {
        Set<Long> ids = employees.stream().map(Employee::getCompanyId).collect(Collectors.toSet());
        return companyRepository.findAllById(ids).stream().collect(Collectors.toMap(Company::getId, c -> c));
    }
}
