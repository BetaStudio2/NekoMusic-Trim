package com.neko.music.handlers;

import com.neko.music.model.ErrorResponse;
import com.neko.music.Main;
import com.neko.music.service.NicknameResolver;
import org.eclipse.jetty.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import com.neko.music.i18n.ApiMessages;

public class UserSearchHandler extends HttpServlet {
    private static final Logger logger = LoggerFactory.getLogger(UserSearchHandler.class);

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        try {
            // 获取查询参数
            String query = request.getParameter("q");
            
            if (query == null || query.trim().isEmpty()) {
                response.setStatus(HttpStatus.BAD_REQUEST_400);
                response.setContentType("application/json;charset=utf-8");
                ErrorResponse errorResponse = new ErrorResponse("user.query_required");
                response.getWriter().println(Main.getObjectMapper().writeValueAsString(errorResponse));
                return;
            }
            
            // 搜索用户
            List<User> results = searchUsers(query);
            
            // 返回结果
            UserSearchResponse searchResponse = new UserSearchResponse(true, 
                "playlist.search_ok", 
                results);
            
            response.setStatus(HttpStatus.OK_200);
            response.setContentType("application/json;charset=utf-8");
            response.getWriter().println(Main.getObjectMapper().writeValueAsString(searchResponse));
            
        } catch (Exception e) {
            response.setStatus(HttpStatus.INTERNAL_SERVER_ERROR_500);
            response.setContentType("application/json;charset=utf-8");
            ErrorResponse errorResponse = new ErrorResponse(ApiMessages.translate("user.search_failed", e.getMessage()));
            response.getWriter().println(Main.getObjectMapper().writeValueAsString(errorResponse));
        }
    }
    
    private List<User> searchUsers(String query) {
        List<User> results = new ArrayList<>();
        try {
            for (NicknameResolver.SearchUser user : Main.getNicknameResolver().search(query)) {
                results.add(new User(user.id(), user.nickname(), user.email(), user.createdAt()));
            }
        } catch (Exception e) {
            logger.error("搜索用户时出错", e);
        }
        return results;
    }
    
    // 内部类用于表示用户对象
    private record User(int id, String nickname, String email, String createdAt) {
    }
    
    // 内部类用于表示搜索响应
    private record UserSearchResponse(boolean success, String message, List<User> users) {
    }
    
    // 内部类用于表示错误响应
}
