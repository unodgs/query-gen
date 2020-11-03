grammar Kpi;
@members {
public void storeAgg(Token agg) {};
}

init: expr EOF ;

expr : left=expr op=(MUL | FMUL | DIV)    right=expr   # MulDiv
     | left=expr op=(PLUS | MINUS) right=expr   # PlusMinus
     | LPAREN expr RPAREN                       # ParenExpr
     | AGG                                      { storeAgg($AGG); } # Agg
     | NUMBER                                   # Number
     ;
     
AGG : LPAREN IDENTIFIER RPAREN ;

LPAREN : '(' ;
RPAREN : ')' ;
PLUS : '+' ;
MINUS : '-' ;
MUL : '*' ;
FMUL : 'mul' ;
DIV : '/' ;

NUMBER : [0-9] + ('.' [0-9]+)? ;
IDENTIFIER : [A-Za-z0-9]+ ;

WS : [ \t\r\n]+ -> skip ;
