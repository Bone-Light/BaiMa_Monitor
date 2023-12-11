package com.example.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.example.entity.dto.Client;
import com.example.entity.vo.request.ClientDetailVO;
import com.example.entity.vo.request.RuntimeDetailVO;

public interface ClientService extends IService<Client> {
    String registerToken();
    Client findClientById(int id);
    Client findClientByToken(String token);
    boolean verifyAndRegister(String token);
    void updateClientDetail(ClientDetailVO vo, Client client);
    void updateRuntimeDetail(RuntimeDetailVO vo, Client client);
}
